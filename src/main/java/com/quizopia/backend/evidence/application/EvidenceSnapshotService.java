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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * D1 (issue #91): student-browser evidence snapshots with honest
 * {@code CLIENT_CAPTURE} provenance (ADR-009 §1).
 *
 * <p><b>Reservation-first concurrency contract (gate review):</b> exactly ONE
 * request owns each {@code idempotencyKey}. The row is INSERTED (PENDING,
 * with its object key) under the key's unique index BEFORE any storage I/O;
 * the insert winner alone uploads and finalizes. A concurrent duplicate
 * loses the insert race and returns the OWNER's row read-only — it can
 * never overwrite the winner's object key, digest or status, and never
 * uploads a second object. Budgets are re-checked inside the same
 * transaction under a per-alert advisory lock, so concurrent uploads with
 * distinct keys cannot exceed 3/alert or 30/attempt. Storage PUT and every
 * failure path run OUTSIDE the lock: the reservation transaction is a few
 * inserts. A crash between PUT and finalize leaves a PENDING row with a
 * known object key — the retention reconciler deletes the object and fails
 * the row after a grace window (no permanent orphans, no silent successes).
 *
 * <p>The server verifies what it CAN verify — attempt ownership +
 * IN_PROGRESS state, frozen policy {@code evidence_snapshots_enabled},
 * alert belongs to this attempt, per-alert/per-attempt bounds, JPEG magic,
 * size cap, declared SHA-256 equality, storage round-trip — and labels the
 * result exactly as what it is: a browser-captured frame. It never claims
 * the frame content was independently verified.
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

    /** Outcome of {@link #upload}: the winning row, or the owner's row on replay. */
    public record UploadOutcome(MonitoringEvidence row, boolean owner, String reason) {

        public boolean idempotentReplay() {
            return !owner;
        }
    }

    /**
     * Stores one student-captured snapshot frame (reservation-first).
     *
     * @param userId         authenticated student
     * @param attemptId      owning attempt
     * @param alertId        the alert whose opening requested the snapshot
     * @param idempotencyKey client retry key (≤128 chars, unique per upload)
     * @param capturedAtMs   client capture wall-clock (epoch ms)
     */
    public UploadOutcome upload(long userId, long attemptId, long alertId,
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

        Instant now = clock.instant();
        Instant capturedAt = Instant.ofEpochMilli(capturedAtMs);
        String objectKey = "snapshots/" + attemptId + "/" + UUID.randomUUID() + ".jpg";

        // ------------------------------------------------------------------
        // RESERVATION (short tx, advisory-locked budget check, no storage I/O)
        // ------------------------------------------------------------------
        Reservation reservation = tx.execute(status -> {
            jdbcLock("evidence-snap:" + alertId);
            jdbcLock("evidence-snap-attempt:" + attemptId);
            // Budget re-check under the lock — concurrent uploads with
            // distinct keys cannot slip past the caps.
            if (evidence.countByAlertIdAndKindAndStatusNot(alertId,
                    MonitoringEvidence.KIND_SNAPSHOT, MonitoringEvidence.STATUS_EXPIRED)
                    >= properties.getSnapshotPerAlert()) {
                return Reservation.rejected("SNAPSHOT_BUDGET_EXHAUSTED");
            }
            if (evidence.countByAttemptIdAndKind(attemptId, MonitoringEvidence.KIND_SNAPSHOT)
                    >= properties.getSnapshotPerAttempt()) {
                return Reservation.rejected("SNAPSHOT_BUDGET_EXHAUSTED");
            }
            // Fast path: an owner already finalized (or holds the key).
            var existing = evidence.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                MonitoringEvidence winner = existing.get();
                if (!winner.getAttemptId().equals(attemptId)) {
                    return Reservation.keyConflict();
                }
                return Reservation.duplicate(winner);
            }
            MonitoringEvidence row = MonitoringEvidence.newSnapshot(UUID.randomUUID(),
                    attempt.getExamSessionId(), attemptId, alertId, idempotencyKey,
                    capturedAt, policy.mediaRetentionExpiry(now), now);
            try {
                MonitoringEvidence inserted = evidence.save(row);
                evidence.flush();
                return Reservation.won(inserted, objectKey);
            } catch (DataIntegrityViolationException race) {
                // Lost the unique-idempotency race: read the winner READ-ONLY.
                MonitoringEvidence winner = evidence.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency winner vanished after conflict"));
                if (!winner.getAttemptId().equals(attemptId)) {
                    return Reservation.keyConflict();
                }
                return Reservation.duplicate(winner);
            }
        });

        if (reservation.rejectedReason != null) {
            rejectSnapshot(attempt, alertId, reservation.rejectedReason);
        }
        if (reservation.keyConflict) {
            throw new MonitoringException(MonitoringErrorCode.EVIDENCE_SNAPSHOT_REJECTED,
                    "idempotencyKey already used by another attempt");
        }
        if (reservation.duplicate != null) {
            // Replay: return the OWNER's row untouched. We never uploaded,
            // never wrote to their row — no overwrite, no second object.
            return new UploadOutcome(reservation.duplicate, false, null);
        }

        // ------------------------------------------------------------------
        // OWNER path: storage I/O outside the transaction/lock
        // ------------------------------------------------------------------
        MonitoringEvidence ownerRow = reservation.row;
        EvidenceStorage.StoredObject stored;
        try {
            stored = storage.put(objectKey, image, "image/jpeg");
        } catch (RuntimeException e) {
            log.warn("snapshot storage unavailable (attempt={}, alert={}): {}",
                    attemptId, alertId, e.getMessage());
            failOwnRow(ownerRow.getId(), "STORAGE_UNAVAILABLE", attempt);
            throw new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE);
        }
        // Browser clock honesty: far-future or far-past captures stay visible
        // through activity metadata; never silently trusted as authoritative.
        MonitoringEvidence finalized = tx.execute(status -> {
            MonitoringEvidence fresh = evidence.findById(ownerRow.getId())
                    .orElseThrow(() -> new IllegalStateException("reservation row vanished"));
            fresh.markAvailable(objectKey, stored.sha256Hex(), stored.bytes(), null, null);
            fresh.setUpdatedAt(clock.instant());
            MonitoringEvidence saved = evidence.save(fresh);
            recordActivity(attempt, alert, saved, capturedAt, stored.bytes());
            return saved;
        });
        log.info("snapshot stored (attempt={}, alert={}, bytes={}, sha256={})",
                attemptId, alertId, stored.bytes(), stored.sha256Hex());
        return new UploadOutcome(finalized, true, null);
    }

    /** Fails OUR OWN row (never another request's) when storage is down. */
    private void failOwnRow(UUID rowId, String reason, Attempt attempt) {
        try {
            tx.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(
                        org.springframework.transaction.TransactionStatus status) {
                    MonitoringEvidence fresh = evidence.findById(rowId).orElse(null);
                    if (fresh == null || !MonitoringEvidence.STATUS_PENDING.equals(fresh.getStatus())) {
                        return; // someone finalized/failed it already
                    }
                    fresh.markFailed(reason);
                    fresh.setUpdatedAt(clock.instant());
                    evidence.save(fresh);
                }
            });
        } catch (RuntimeException e) {
            log.warn("failed-row write failed (row={}): {}", rowId, e.getMessage());
            // Row stays PENDING → reconciler fails it after the grace window.
        }
    }

    /** Per-key advisory lock inside the open transaction (short-held). */
    private void jdbcLock(String key) {
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?))")
                    .setParameter(1, key).getSingleResult();
        } catch (RuntimeException e) {
            throw new IllegalStateException("advisory lock failed: " + e.getMessage(), e);
        }
    }

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

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

    /** Result of the reservation transaction. */
    private static final class Reservation {
        MonitoringEvidence row;          // we own it (PENDING)
        MonitoringEvidence duplicate;    // owner's row — replay, read-only
        String rejectedReason;
        boolean keyConflict;

        static Reservation won(MonitoringEvidence row, String objectKey) {
            Reservation r = new Reservation();
            r.row = row;
            return r;
        }

        static Reservation duplicate(MonitoringEvidence winner) {
            Reservation r = new Reservation();
            r.duplicate = winner;
            return r;
        }

        static Reservation rejected(String reason) {
            Reservation r = new Reservation();
            r.rejectedReason = reason;
            return r;
        }

        static Reservation keyConflict() {
            Reservation r = new Reservation();
            r.keyConflict = true;
            return r;
        }
    }
    /** Test-visible digest helper (same algorithm the storage layer uses). */
    public static String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content))
                    .toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
