package com.quizopia.backend.evidence.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.domain.MonitoringEvidenceAccess;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceAccessRepository;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.evidence.repository.MonitoringRecordingJobRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.media.storage.EvidenceStorageProperties;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D4 (issue #94): teacher-scoped evidence reads, audited signed URLs and
 * the per-attempt recordings/segments view. Authorization mirrors the
 * monitoring convention: permission + session ownership, generic denial
 * with no existence leak; every URL mint writes an access-audit row.
 */
@Service
public class EvidenceQueryService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceQueryService.class);
    private static final int MAX_PAGE = 100;

    private final MonitoringEvidenceRepository evidence;
    private final MonitoringEvidenceAccessRepository accessLog;
    private final MonitoringRecordingJobRepository jobs;
    private final AttemptRepository attempts;
    private final EvidenceStorage storage;
    private final EvidenceStorageProperties storageProperties;
    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessions;
    private final EvidencePolicyReader policyReader;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EvidenceQueryService(MonitoringEvidenceRepository evidence,
            MonitoringEvidenceAccessRepository accessLog,
            MonitoringRecordingJobRepository jobs,
            AttemptRepository attempts,
            EvidenceStorage storage,
            EvidenceStorageProperties storageProperties,
            AttemptAuthorizationService auth,
            ExamSessionRepository sessions,
            EvidencePolicyReader policyReader,
            TransactionTemplate tx,
            Clock clock) {
        this.evidence = evidence;
        this.accessLog = accessLog;
        this.jobs = jobs;
        this.attempts = attempts;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.auth = auth;
        this.sessions = sessions;
        this.policyReader = policyReader;
        this.tx = tx;
        this.clock = clock;
    }

    /** Evidence metadata page (no URLs) for an owned session. */
    public List<MonitoringEvidence> listEvidence(long teacherUserId, long sessionId,
            Long alertId, String kind, int limit) {
        requireTeacherReader(teacherUserId, sessionId);
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE);
        if (alertId != null) {
            return evidence.findByExamSessionIdAndAlertIdOrderByCreatedAtDesc(sessionId, alertId);
        }
        if (kind != null && !kind.isBlank()) {
            return evidence.findByExamSessionIdAndKindOrderByCreatedAtDesc(
                    sessionId, kind.toUpperCase(), PageRequest.of(0, pageSize));
        }
        return evidence.findByExamSessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, pageSize));
    }

    /**
     * Mints a short-lived presigned GET (audited). EXPIRED or FAILED rows
     * are refused — the API never hands out a dead URL.
     */
    public SignedUrl mintPlaybackUrl(long teacherUserId, long sessionId, UUID evidenceId) {
        requireTeacherReader(teacherUserId, sessionId);
        MonitoringEvidence row = evidence.findByIdAndExamSessionId(evidenceId, sessionId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.EVIDENCE_NOT_FOUND));
        if (MonitoringEvidence.STATUS_EXPIRED.equals(row.getStatus())
                || MonitoringEvidence.STATUS_FAILED.equals(row.getStatus())
                || MonitoringEvidence.STATUS_PENDING.equals(row.getStatus())
                || row.getObjectKey() == null) {
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_CLIP_REJECTED,
                    "evidence not playable in status " + row.getStatus());
        }
        String url = storage.presignedGet(row.getObjectKey(), storageProperties.getPresignTtl());
        tx.executeWithoutResult(status -> accessLog.save(MonitoringEvidenceAccess.of(
                row.getId(), teacherUserId, MonitoringEvidenceAccess.ACTION_URL_MINTED,
                clock.instant())));
        return new SignedUrl(url, clock.instant().plus(storageProperties.getPresignTtl()));
    }

    /** Per-attempt recording segments + derived gaps (teacher view). */
    public List<MonitoringRecordingJob> recordings(long teacherUserId, long sessionId) {
        requireTeacherReader(teacherUserId, sessionId);
        return jobs.findBySessionOrdered(sessionId);
    }

    /**
     * P1-3: teacher-only playback/download URL for a FULL/ROLLING segment.
     * Gates: permission + session ownership, job belongs to the session,
     * status COMPLETE, object present, not object_deleted, not past expiry.
     * Every mint is audited (recording target).
     */
    public SignedUrl recordingPlaybackUrl(long teacherUserId, long sessionId,
            java.util.UUID jobId) {
        requireTeacherReader(teacherUserId, sessionId);
        MonitoringRecordingJob job = jobs.findById(jobId)
                .filter(j -> j.getExamSessionId().equals(sessionId))
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.EVIDENCE_NOT_FOUND));
        if (!MonitoringRecordingJob.STATUS_COMPLETE.equals(job.getStatus())
                || job.getObjectKey() == null
                || job.isObjectDeleted()
                || (job.getExpiresAt() != null
                        && !job.getExpiresAt().isAfter(clock.instant()))) {
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_CLIP_REJECTED,
                    "recording not playable in status " + job.getStatus()
                            + (job.isObjectDeleted() ? " (object deleted)" : ""));
        }
        String url = storage.presignedGet(job.getObjectKey(), storageProperties.getPresignTtl());
        tx.executeWithoutResult(status -> accessLog.save(MonitoringEvidenceAccess.ofRecording(
                job.getId(), teacherUserId, MonitoringEvidenceAccess.ACTION_URL_MINTED,
                clock.instant())));
        return new SignedUrl(url, clock.instant().plus(storageProperties.getPresignTtl()));
    }

    /** Student-facing media status projection (read-only, own attempt). */
    public AttemptMediaStatus mediaStatus(long userId, long attemptId) {
        var attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (!attempt.getUserId().equals(userId)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        EvidencePolicyReader.MediaPolicy policy = policyOf(attempt.getExamSessionId());
        return new AttemptMediaStatus(policy.fullRecordingEnabled(),
                policy.rollingBufferActive(), policy.snapshotsEnabled());
    }

    private EvidencePolicyReader.MediaPolicy policyOf(long sessionId) {
        return policyReader.policyOf(sessionId);
    }

    /** Signed playback URL outcome. */
    public record SignedUrl(String url, java.time.Instant expiresAt) {
    }

    /** Student media-status projection (indicator + first-join notice). */
    public record AttemptMediaStatus(boolean fullRecordingEnabled,
            boolean rollingBufferActive, boolean snapshotsEnabled) {
    }

    /** Shared authorization gate (permission + session ownership). Public so
     *  destructive endpoints (e.g. demo purge) authorize EXPLICITLY instead
     *  of relying on a read probe's side effect (security review F-1). */
    public void requireTeacherReader(long userId, long sessionId) {
        try {
            auth.requireLearnerWithPermission(userId, "MONITORING_EVIDENCE_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        Optional<com.quizopia.backend.exam.domain.model.ExamSession> session =
                sessions.findById(sessionId)
                        .filter(s -> java.util.Objects.equals(s.getCreatedBy(), userId));
        if (session.isEmpty()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
    }
}
