package com.quizopia.backend.evidence.application;

import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceAccessRepository;
import com.quizopia.backend.evidence.repository.MonitoringEvidenceRepository;
import com.quizopia.backend.evidence.repository.MonitoringRecordingJobRepository;
import com.quizopia.backend.media.storage.EvidenceStorage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D4 retention (issue #94, ADR-009 §2.6): hourly pass that expires evidence
 * past its per-session retention (object delete + EXPIRED metadata), sweeps
 * the 90-day access-audit ledger, and reconciles rolling-buffer orphans
 * (objects without a live row reference). Failures are logged and retried
 * next cycle — retention never blocks anything else.
 */
@Component
public class EvidenceRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetentionJob.class);
    private static final int BATCH = 200;

    private final MonitoringEvidenceRepository evidence;
    private final MonitoringEvidenceAccessRepository accessLog;
    private final MonitoringRecordingJobRepository jobs;
    private final EvidenceStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EvidenceRetentionJob(MonitoringEvidenceRepository evidence,
            MonitoringEvidenceAccessRepository accessLog,
            MonitoringRecordingJobRepository jobs,
            EvidenceStorage storage,
            TransactionTemplate tx,
            Clock clock) {
        this.evidence = evidence;
        this.accessLog = accessLog;
        this.jobs = jobs;
        this.storage = storage;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void run() {
        expireEvidence();
        expireRecordings();
        sweepAccessLog();
        try {
            for (Long attemptId : jobs.findAttemptsWithRollingRows()) {
                reconcileRollingOrphans(attemptId);
            }
        } catch (RuntimeException e) {
            log.warn("orphan reconcile pass failed: {}", e.getMessage());
        }
    }

    /** Media retention: object delete first, then EXPIRED metadata. */
    public void expireEvidence() {
        Instant now = clock.instant();
        List<MonitoringEvidence> due = evidence.findExpired(now, PageRequest.of(0, BATCH));
        for (MonitoringEvidence row : due) {
            if (!deleteObjectHonestly(row.getObjectKey())) {
                continue; // keep the row non-EXPIRED until the object is gone
            }
            tx.executeWithoutResult(status -> evidence.markExpired(
                    List.of(row.getId()), Timestamp.from(now)));
        }
        if (!due.isEmpty()) {
            log.info("retention expired {} evidence rows", due.size());
        }
    }

    /**
     * P1-1: 30-day retention for FULL/ROLLING recording objects. Deletes the
     * object, then marks {@code object_deleted = true} per row — rows stay
     * as metadata/gap evidence. Honesty: a row is flagged ONLY after a
     * successful delete or a verified absence (see {@link #deleteObjectHonestly}).
     */
    public void expireRecordings() {
        Instant now = clock.instant();
        List<com.quizopia.backend.evidence.domain.MonitoringRecordingJob> due =
                jobs.findExpired(now);
        for (com.quizopia.backend.evidence.domain.MonitoringRecordingJob job : due) {
            if (!deleteObjectHonestly(job.getObjectKey())) {
                continue; // retried next cycle
            }
            tx.executeWithoutResult(status -> jobs.markObjectDeleted(
                    job.getId(), Timestamp.from(now)));
        }
        if (!due.isEmpty()) {
            log.info("retention expired {} recording objects", due.size());
        }
    }

    /**
     * P1-2 false-deletion guard: true ONLY when the object is really gone —
     * delete succeeded, or HEAD confirms absence (already removed out-of-
     * band). A storage OUTAGE (delete AND head both failing) returns false:
     * no fake success, the caller retries next cycle.
     */
    private boolean deleteObjectHonestly(String objectKey) {
        if (objectKey == null) {
            return true; // nothing to delete — target state already holds
        }
        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException deleteFailed) {
            try {
                return storage.head(objectKey).isEmpty();
            } catch (RuntimeException headFailed) {
                log.warn("retention delete+verify failed (key={}): delete={}, head={}",
                        objectKey, deleteFailed.getMessage(), headFailed.getMessage());
                return false;
            }
        }
    }

    /** Activity-metadata class: access audit lives 90 days. */
    public void sweepAccessLog() {
        Instant cutoff = clock.instant().minus(90, ChronoUnit.DAYS);
        Integer deleted = tx.execute(status -> accessLog.deleteOlderThan(cutoff));
        if (deleted != null && deleted > 0) {
            log.info("swept {} evidence access-audit rows", deleted);
        }
    }

    /**
     * Orphan reconciliation (issue #94): deletes storage objects of an
     * attempt that no row references anymore — rolling pulses whose row was
     * superseded, plus snapshot objects orphaned by a lost insert race
     * (PUT succeeded, the metadata transaction did not). Skips cycles where
     * anything is in flight (an upload in progress is not yet referenced).
     */
    public int reconcileRollingOrphans(long attemptId) {
        if (!jobs.findActiveByAttemptId(attemptId).isEmpty()) {
            return 0; // something in flight — skip this cycle
        }
        java.util.List<String> referenced = jobs.findRollingNewestFirst(attemptId).stream()
                .map(com.quizopia.backend.evidence.domain.MonitoringRecordingJob::getObjectKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        evidence.findByAttemptId(attemptId).forEach(row -> {
            if (row.getObjectKey() != null) {
                referenced.add(row.getObjectKey());
            }
        });
        int deleted = 0;
        for (String prefix : java.util.List.of(
                "rolling/" + attemptId + "/", "snapshots/" + attemptId + "/")) {
            for (String key : storage.list(prefix)) {
                if (!referenced.contains(key)) {
                    try {
                        storage.delete(key);
                        deleted++;
                    } catch (RuntimeException e) {
                        log.warn("orphan delete failed (key={}): {}", key, e.getMessage());
                    }
                }
            }
        }
        if (deleted > 0) {
            log.info("reconciled {} orphan objects (attempt={})", deleted, attemptId);
        }
        return deleted;
    }

    /**
     * Demo purge: delete all live evidence AND recording objects of a session
     * (audit kept). P1-2: a row is marked EXPIRED / object_deleted ONLY when
     * its object is really gone; survivors stay truthfully untouched for the
     * next cycle — no fake success, no skipped objects.
     */
    public int purgeSession(long sessionId) {
        int deleted = 0;
        Instant now = clock.instant();
        for (MonitoringEvidence row : evidence.findPurgeable(sessionId)) {
            if (deleteObjectHonestly(row.getObjectKey())) {
                tx.executeWithoutResult(status -> evidence.markExpired(
                        List.of(row.getId()), Timestamp.from(now)));
                if (row.getObjectKey() != null) {
                    deleted++;
                }
            }
        }
        for (com.quizopia.backend.evidence.domain.MonitoringRecordingJob job
                : jobs.findBySessionOrdered(sessionId)) {
            if (job.getObjectKey() != null && !job.isObjectDeleted()
                    && deleteObjectHonestly(job.getObjectKey())) {
                tx.executeWithoutResult(status -> jobs.markObjectDeleted(
                        job.getId(), Timestamp.from(now)));
                deleted++;
            }
        }
        return deleted;
    }
}
