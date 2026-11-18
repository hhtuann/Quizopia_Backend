package com.quizopia.backend.evidence.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.evidence.application.FfmpegRunner.SegmentInput;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.evidence.repository.MonitoringRecordingJobRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.media.storage.EvidenceStorageProperties;
import com.quizopia.backend.monitoring.application.ActivityEventRecorder;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringAiAlertRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * D3 (issue #93): alert clip extraction with honest coverage (ADR-009 §2.5).
 *
 * <p>Recording ON → clips cut from {@code FULL_RECORDING} segments.
 * Recording OFF + clips ON → clips cut from {@code ROLLING_BUFFER} pulses
 * that were running BEFORE the alert (buffer-before-alert honesty). No
 * covering footage → {@code FAILED/NO_COVERAGE} — a clip is never
 * fabricated from screenshots, stale files or synthesized video.
 *
 * <p>Coverage math: the request window (alert first−pre … last+post, capped)
 * widened by the clock-skew margin, intersected with segment intervals;
 * sub-threshold gaps are pulse-restart cost and ignored; real gaps keep the
 * clip honestly {@code PARTIAL} with a machine-readable gap list.
 */
@Service
public class ClipService {

    private static final Logger log = LoggerFactory.getLogger(ClipService.class);

    private final MonitoringAiAlertRepository alerts;
    private final MonitoringEvidenceRepository evidence;
    private final MonitoringRecordingJobRepository jobs;
    private final AttemptRepository attempts;
    private final EvidenceStorage storage;
    private final EvidenceStorageProperties storageProperties;
    private final EvidenceProperties properties;
    private final EvidencePolicyReader policyReader;
    private final FfmpegRunner ffmpeg;
    private final ActivityEventRecorder activity;
    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessions;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public ClipService(MonitoringAiAlertRepository alerts,
            MonitoringEvidenceRepository evidence,
            MonitoringRecordingJobRepository jobs,
            AttemptRepository attempts,
            EvidenceStorage storage,
            EvidenceStorageProperties storageProperties,
            EvidenceProperties properties,
            EvidencePolicyReader policyReader,
            FfmpegRunner ffmpeg,
            ActivityEventRecorder activity,
            AttemptAuthorizationService auth,
            ExamSessionRepository sessions,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock) {
        this.alerts = alerts;
        this.evidence = evidence;
        this.jobs = jobs;
        this.attempts = attempts;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.properties = properties;
        this.policyReader = policyReader;
        this.ffmpeg = ffmpeg;
        this.activity = activity;
        this.auth = auth;
        this.sessions = sessions;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Teacher request: idempotently creates and synchronously extracts a
     * clip for an alert of an OWNED session.
     */
    public MonitoringEvidence requestClip(long teacherUserId, long sessionId, long alertId) {
        requireTeacherReader(teacherUserId, sessionId);
        MonitoringAiAlert alert = alerts.findById(alertId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.EVIDENCE_NOT_FOUND));
        if (!alert.getExamSessionId().equals(sessionId)) {
            // Anti-enumeration: alert of another session is a plain miss.
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_NOT_FOUND);
        }
        EvidencePolicyReader.MediaPolicy policy = policyReader.policyOf(sessionId);
        if (!policy.clipsEnabled()) {
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_CLIP_REJECTED,
                    "clips disabled for this session");
        }
        String provenance = policy.fullRecordingEnabled()
                ? MonitoringEvidence.PROVENANCE_FULL_RECORDING
                : MonitoringEvidence.PROVENANCE_ROLLING_BUFFER;

        Optional<MonitoringEvidence> reusable = evidence.findReusableClip(alertId, provenance);
        if (reusable.isPresent()) {
            MonitoringEvidence existing = reusable.get();
            if (!MonitoringEvidence.STATUS_PENDING.equals(existing.getStatus())) {
                return existing;
            }
            // Stale PENDING (process died mid-extraction): the eternal-spinner
            // trap — fail the old row honestly and extract afresh (review P2).
            if (existing.getUpdatedAt() != null && existing.getUpdatedAt()
                    .isBefore(clock.instant().minus(properties.getFfmpegTimeout().multipliedBy(2)))) {
                attempts.findById(alert.getAttemptId())
                        .ifPresentOrElse(
                                attemptRow -> finishFailure(existing, attemptRow,
                                        "STALE_PENDING", null),
                                () -> {
                                    existing.markFailed("STALE_PENDING");
                                    existing.setUpdatedAt(clock.instant());
                                    tx.execute(status -> evidence.save(existing));
                                });
            } else {
                return existing; // a live extraction is running right now
            }
        }

        var attempt = attempts.findById(alert.getAttemptId())
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.EVIDENCE_CLIP_REJECTED,
                        "attempt missing"));
        Instant now = clock.instant();
        Instant windowStart = alert.getFirstDetectedAt().minus(properties.getClipPreRoll());
        Instant windowEnd = alert.getLastDetectedAt().plus(properties.getClipPostRoll());
        // Hard cap: shrink post-roll first, then pre-roll.
        if (Duration.between(windowStart, windowEnd).compareTo(properties.getClipMaxWindow()) > 0) {
            windowEnd = windowStart.plus(properties.getClipMaxWindow());
        }
        MonitoringEvidence clip = MonitoringEvidence.newClip(UUID.randomUUID(), sessionId,
                alert.getAttemptId(), alertId, provenance, windowStart, windowEnd,
                policy.mediaRetentionExpiry(now), now);
        MonitoringEvidence saved = tx.execute(status -> evidence.save(clip));
        recordClipActivity(attempt, saved, ActivityEventType.EVIDENCE_CLIP_REQUESTED, null);
        return extract(saved, alert, attempt);
    }

    /** Runs the extraction pipeline for a PENDING clip row. */
    private MonitoringEvidence extract(MonitoringEvidence clip, MonitoringAiAlert alert,
            com.quizopia.backend.attempt.domain.model.Attempt attempt) {
        String kind = MonitoringEvidence.PROVENANCE_FULL_RECORDING.equals(clip.getProvenance())
                ? MonitoringRecordingJob.KIND_FULL
                : MonitoringRecordingJob.KIND_ROLLING;
        Instant marginStart = clip.getWindowStart().minus(properties.getClockSkewMargin());
        Instant marginEnd = clip.getWindowEnd().plus(properties.getClockSkewMargin());
        List<MonitoringRecordingJob> covering = jobs.findCovering(
                clip.getAttemptId(), kind, marginStart, marginEnd);

        if (covering.isEmpty()) {
            return finishFailure(clip, attempt, "NO_COVERAGE",
                    "no " + kind + " footage covers the alert window");
        }

        Coverage coverage = computeCoverage(clip.getWindowStart(), clip.getWindowEnd(), covering);
        if (coverage.effective().isEmpty()) {
            return finishFailure(clip, attempt, "NO_COVERAGE",
                    "covering segments exist but none overlap the window after skew");
        }

        List<SegmentInput> inputs = new ArrayList<>();
        for (Covered part : coverage.effective()) {
            String url = storage.presignedGetInternal(part.objectKey(),
                    properties.getFfmpegTimeout().plus(Duration.ofSeconds(30)));
            inputs.add(new SegmentInput(url, part.inSeconds(), part.outSeconds()));
        }
        if (inputs.size() == 1 && coverage.gaps().isEmpty()
                && coversWholeWindow(coverage.effective().get(0), clip)) {
            // Single full-cover segment: plain -ss/-to copy (no concat).
            return runFfmpegDirect(clip, attempt, inputs.get(0));
        }
        return runFfmpegConcat(clip, attempt, inputs, coverage);
    }

    private MonitoringEvidence runFfmpegDirect(MonitoringEvidence clip,
            com.quizopia.backend.attempt.domain.model.Attempt attempt, SegmentInput input) {
        // Represented as a one-entry concat — identical code path, single
        // input keeps ffmpeg on stream copy.
        return runFfmpegConcat(clip, attempt, List.of(input),
                new Coverage(List.of(), List.of()));
    }

    private MonitoringEvidence runFfmpegConcat(MonitoringEvidence clip,
            com.quizopia.backend.attempt.domain.model.Attempt attempt,
            List<SegmentInput> inputs, Coverage coverage) {
        FfmpegRunner.Result result;
        try {
            result = ffmpeg.extract(inputs);
        } catch (FfmpegRunner.FfmpegUnavailable e) {
            return finishFailure(clip, attempt, "FFMPEG_UNAVAILABLE", e.getMessage());
        }
        if (result instanceof FfmpegRunner.Result.Failed failed) {
            return finishFailure(clip, attempt, failed.reason(), null);
        }
        FfmpegRunner.Result.Ok ok = (FfmpegRunner.Result.Ok) result;
        Path output = ok.output();
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(output);
            String objectKey = "clips/" + clip.getId() + ".mp4";
            EvidenceStorage.StoredObject stored = storage.put(objectKey, bytes, "video/mp4");
            String coverageJson = coverageJson(clip, coverage);
            boolean partial = !coverage.gaps().isEmpty();
            clip.markPartial(objectKey, stored.sha256Hex(), stored.bytes(),
                    json.readTree(coverageJson));
            if (!partial) {
                clip.markAvailable(objectKey, stored.sha256Hex(), stored.bytes(), null, null);
            }
            clip.setUpdatedAt(clock.instant());
            MonitoringEvidence saved = tx.execute(status -> evidence.save(clip));
            recordClipActivity(attempt, saved,
                    partial ? ActivityEventType.EVIDENCE_CLIP_READY : ActivityEventType.EVIDENCE_CLIP_READY,
                    coverageJson);
            log.info("clip extracted (id={}, status={}, bytes={}, gaps={})",
                    clip.getId(), saved.getStatus(), stored.bytes(), coverage.gaps().size());
            return saved;
        } catch (RuntimeException | java.io.IOException e) {
            return finishFailure(clip, attempt, "STORAGE_UNAVAILABLE", e.getMessage());
        } finally {
            ffmpeg.cleanup(output);
        }
    }

    // ==================================================================
    // Coverage math
    // ==================================================================

    private record Covered(String objectKey, Instant from, Instant to,
            double inSeconds, double outSeconds) {
    }

    private record Coverage(List<Covered> effective, List<Instant[]> gaps) {
    }

    private Coverage computeCoverage(Instant windowStart, Instant windowEnd,
            List<MonitoringRecordingJob> segments) {
        List<Instant[]> gaps = new ArrayList<>();
        List<Covered> parts = new ArrayList<>();
        Instant cursor = windowStart;
        for (MonitoringRecordingJob segment : segments) {
            Instant from = segment.getStartedAt();
            Instant to = segment.getEndedAt();
            if (to == null || from == null || to.compareTo(from) <= 0) {
                continue;
            }
            // Segment fully before the cursor → no gap contribution.
            if (!to.isAfter(cursor)) {
                continue;
            }
            Instant overlapFrom = cursor.isAfter(from) ? cursor : from;
            if (overlapFrom.isAfter(windowEnd)) {
                break;
            }
            Instant overlapTo = to.isAfter(windowEnd) ? windowEnd : to;
            if (overlapFrom.isBefore(overlapTo)) {
                Duration gap = Duration.between(cursor, overlapFrom);
                if (!gap.isNegative() && gap.compareTo(properties.getGapIgnoreThreshold()) >= 0) {
                    gaps.add(new Instant[]{cursor, overlapFrom});
                }
                double inSeconds = Duration.between(from, overlapFrom).toMillis() / 1000.0;
                double outSeconds = Duration.between(from, overlapTo).toMillis() / 1000.0;
                parts.add(new Covered(segment.getObjectKey(), overlapFrom, overlapTo,
                        inSeconds, outSeconds));
                cursor = overlapTo;
            }
            if (!cursor.isBefore(windowEnd)) {
                break;
            }
        }
        // Tail gap after the last covered part.
        if (cursor.isBefore(windowEnd)
                && Duration.between(cursor, windowEnd)
                        .compareTo(properties.getGapIgnoreThreshold()) >= 0) {
            gaps.add(new Instant[]{cursor, windowEnd});
        }
        return new Coverage(parts, gaps);
    }

    private boolean coversWholeWindow(Covered part, MonitoringEvidence clip) {
        return !part.from().isAfter(clip.getWindowStart())
                && !part.to().isBefore(clip.getWindowEnd());
    }

    private String coverageJson(MonitoringEvidence clip, Coverage coverage) {
        ObjectNode node = json.createObjectNode();
        node.put("windowStart", clip.getWindowStart().toString());
        node.put("windowEnd", clip.getWindowEnd().toString());
        ArrayNode covered = node.putArray("covered");
        for (Covered part : coverage.effective()) {
            ObjectNode c = covered.addObject();
            c.put("from", part.from().toString());
            c.put("to", part.to().toString());
            c.put("objectKey", part.objectKey());
        }
        ArrayNode gapArray = node.putArray("gaps");
        for (Instant[] gap : coverage.gaps()) {
            ObjectNode g = gapArray.addObject();
            g.put("from", gap[0].toString());
            g.put("to", gap[1].toString());
        }
        return node.toString();
    }

    private MonitoringEvidence finishFailure(MonitoringEvidence clip,
            com.quizopia.backend.attempt.domain.model.Attempt attempt,
            String reason, String detail) {
        clip.markFailed(reason);
        clip.setUpdatedAt(clock.instant());
        MonitoringEvidence saved = tx.execute(status -> evidence.save(clip));
        recordClipActivity(attempt, saved, ActivityEventType.EVIDENCE_CLIP_FAILED, reason);
        log.info("clip failed (id={}, reason={})", clip.getId(), reason);
        return saved;
    }

    private void recordClipActivity(com.quizopia.backend.attempt.domain.model.Attempt attempt,
            MonitoringEvidence clip, ActivityEventType type, String detail) {
        try {
            ObjectNode meta = json.createObjectNode();
            meta.put("evidenceId", clip.getId().toString());
            meta.put("alertId", clip.getAlertId());
            meta.put("provenance", clip.getProvenance());
            if (detail != null) {
                meta.put("detail", detail);
            }
            tx.executeWithoutResult(s -> activity.recordServerEvent(
                    attempt.getId(), attempt.getExamSessionId(),
                    attempt.getUserId(), type, clock.instant(), meta.toString()));
        } catch (RuntimeException e) {
            log.debug("clip activity write failed: {}", e.getMessage());
        }
    }

    private void requireTeacherReader(long userId, long sessionId) {
        try {
            auth.requireLearnerWithPermission(userId, "MONITORING_EVIDENCE_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        sessions.findById(sessionId)
                .filter(s -> java.util.Objects.equals(s.getCreatedBy(), userId))
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }
}
