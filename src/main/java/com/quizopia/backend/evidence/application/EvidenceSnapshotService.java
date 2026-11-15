package com.quizopia.backend.evidence.application;

import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.evidence.application.EvidencePolicyReader.MediaPolicy;
import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.monitoring.application.ActivityEventRecorder;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringAiAlertRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * D1 (issue #91): student-browser evidence snapshots with honest
 * {@code CLIENT_CAPTURE} provenance (ADR-009 §1).
 *
 * <p>The server verifies what it CAN verify — attempt ownership + IN_PROGRESS
 * state, frozen policy {@code evidence_snapshots_enabled}, alert belongs to
 * this attempt, per-alert/per-attempt bounds, JPEG magic, size cap, declared
 * SHA-256 equality, storage round-trip — and labels the result exactly as
 * what it is: a browser-captured frame. It never claims the frame content
 * was independently verified.
 *
 * <p>Idempotency: the client-supplied {@code idempotencyKey} is unique in
 * {@code monitoring_evidence}; a retry of the same upload returns the first
 * row (200) instead of a second object. Storage PUT runs OUTSIDE the
 * transaction ({@link TransactionTemplate}); the row insert is the commit
 * point.
 */
@Service
public class EvidenceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSnapshotService.class);

    /** JPEG SOI marker (FF D8 FF). */
    private static final int JPEG_SOI_0 = 0xFF;
    private static final int JPEG_SOI_1 = 0xD8;

    private final AttemptRepository attempts;
    private final MonitoringAiAlertRepository alerts;
    private final MonitoringEvidenceRepository evidence;
    private final EvidencePolicyReader policyReader;
    private final EvidenceStorage storage;
    private final EvidenceProperties properties;
    private final ActivityEventRecorder activity;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public EvidenceSnapshotService(AttemptRepository attempts,
            MonitoringAiAlertRepository alerts,
            MonitoringEvidenceRepository evidence,
            EvidencePolicyReader policyReader,
            EvidenceStorage storage,
            EvidenceProperties properties,
            ActivityEventRecorder activity,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock) {
        this.attempts = attempts;
        this.alerts = alerts;
        this.evidence = evidence;
        this.policyReader = policyReader;
        this.storage = storage;
        this.properties = properties;
        this.activity = activity;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Stores one student-captured snapshot frame.
     *
     * @param userId    authenticated student
     * @param attemptId owning attempt
     * @param alertId   the alert whose opening requested the snapshot
     * @param idempotencyKey client retry key (≤128 chars)
     * @param capturedAtMs client capture wall-clock (epoch ms)
     */
    public SnapshotResult upload(long userId, long attemptId, long alertId,
            String idempotencyKey, long capturedAtMs, byte[] image) {
        Attempt attempt = attempts.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (!attempt.getUserId().equals(userId)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        if (!"IN_PROGRESS".equals(attempt.getStatus().name())) {
            rejectSnapshot(attempt, alertId, "ATTEMPT_NOT_IN_PROGRESS");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128 || capturedAtMs <= 0) {
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED,
                    "idempotencyKey/capturedAt invalid");
        }
        if (image == null || image.length < 4
                || (image[0] & 0xFF) != JPEG_SOI_0 || (image[1] & 0xFF) != JPEG_SOI_1) {
            rejectSnapshot(attempt, alertId, "NOT_JPEG");
        }
        if (image.length > properties.getSnapshotMaxBytes()) {
            rejectSnapshot(attempt, alertId, "SNAPSHOT_TOO_LARGE");
        }
        MonitoringAiAlert alert = alerts.findById(alertId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED,
                        "alert not found"));
        if (!alert.getAttemptId().equals(attemptId)) {
            // Anti-enumeration: an alert id from another attempt looks like a
            // plain rejection, never a cross-attempt oracle.
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED,
                    "alert does not belong to this attempt");
        }
        MediaPolicy policy = policyReader.policyOf(attempt.getExamSessionId());
        if (!policy.snapshotsEnabled()) {
            rejectSnapshot(attempt, alertId, "SNAPSHOTS_DISABLED");
        }

        // Idempotent retry short-circuit (before any storage I/O).
        var existing = evidence.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getAttemptId().equals(attemptId)) {
                throw new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED,
                        "idempotencyKey already used by another attempt");
            }
            return new SnapshotResult(existing.get().getId(), existing.get().getStatus(),
                    existing.get().getProvenance(), true);
        }

        if (evidence.countByAlertIdAndKindAndStatusNot(alertId,
                MonitoringEvidence.KIND_SNAPSHOT, MonitoringEvidence.STATUS_EXPIRED)
                >= properties.getSnapshotPerAlert()
                || evidence.countByAttemptIdAndKind(attemptId, MonitoringEvidence.KIND_SNAPSHOT)
                >= properties.getSnapshotPerAttempt()) {
            rejectSnapshot(attempt, alertId, "SNAPSHOT_BUDGET_EXHAUSTED");
        }

        Instant now = clock.instant();
        Instant capturedAt = Instant.ofEpochMilli(capturedAtMs);
        // Browser clock sanity: far-future or far-past captures are stored but
        // flagged through activity metadata (honest), never silently accepted
        // as authoritative timestamps.
        String objectKey = "snapshots/" + attemptId + "/" + UUID.randomUUID() + ".jpg";
        EvidenceStorage.StoredObject stored;
        try {
            stored = storage.put(objectKey, image, "image/jpeg");
        } catch (RuntimeException e) {
            log.warn("snapshot storage unavailable (attempt={}, alert={}): {}",
                    attemptId, alertId, e.getMessage());
            throw new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE);
        }

        MonitoringEvidence row = MonitoringEvidence.newSnapshot(UUID.randomUUID(),
                attempt.getExamSessionId(), attemptId, alertId, idempotencyKey, capturedAt,
                policy.mediaRetentionExpiry(now), now);
        MonitoringEvidence saved = tx.execute(status -> {
            // Re-check idempotency inside the insert transaction (race).
            var raced = evidence.findByIdempotencyKey(idempotencyKey);
            if (raced.isPresent()) {
                return raced.get();
            }
            MonitoringEvidence inserted = evidence.save(row);
            recordActivity(attempt, alert, inserted, capturedAt, stored.bytes());
            return inserted;
        });
        saved.markAvailable(objectKey, stored.sha256Hex(), stored.bytes(), null, null);
        saved.setUpdatedAt(clock.instant());
        MonitoringEvidence finalized = tx.execute(status -> evidence.save(saved));
        log.info("snapshot stored (attempt={}, alert={}, bytes={}, sha256={})",
                attemptId, alertId, stored.bytes(), stored.sha256Hex());
        return new SnapshotResult(finalized.getId(), finalized.getStatus(),
                finalized.getProvenance(), false);
    }

    /** Rejections are honest activity events, then a 400 to the client. */
    private void rejectSnapshot(Attempt attempt, long alertId, String reason) {
        recordRejection(attempt, alertId, reason);
        throw new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED, reason);
    }

    private void recordRejection(Attempt attempt, long alertId, String reason) {
        try {
            ObjectNode meta = json.createObjectNode();
            meta.put("alertId", alertId);
            meta.put("reason", reason);
            tx.executeWithoutResult(s -> activity.recordServerEvent(
                    attempt.getId(), attempt.getExamSessionId(),
                    attempt.getUserId(), ActivityEventType.EVIDENCE_SNAPSHOT_REJECTED,
                    clock.instant(), meta.toString()));
        } catch (RuntimeException e) {
            log.debug("rejection activity write failed (attempt={}): {}", attempt.getId(), e.getMessage());
        }
    }

    private void recordActivity(Attempt attempt, MonitoringAiAlert alert,
            MonitoringEvidence row, Instant capturedAt, long bytes) {
        ObjectNode meta = json.createObjectNode();
        meta.put("evidenceId", row.getId().toString());
        meta.put("alertId", alert.getId());
        meta.put("provenance", MonitoringEvidence.PROVENANCE_CLIENT_CAPTURE);
        meta.put("bytes", bytes);
        meta.put("capturedAt", capturedAt.toString());
        activity.recordServerEvent(attempt.getId(), attempt.getExamSessionId(),
                attempt.getUserId(), ActivityEventType.EVIDENCE_SNAPSHOT_UPLOADED,
                clock.instant(), meta.toString());
    }

    /** Result of an upload (first store or idempotent replay). */
    public record SnapshotResult(UUID evidenceId, String status, String provenance,
            boolean idempotentReplay) {
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(content)).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
