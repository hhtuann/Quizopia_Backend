package com.quizopia.backend.classroom.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import com.quizopia.backend.classroom.repository.ClassroomRosterEntryRepository;
import org.springframework.stereotype.Component;

/**
 * Identity-M3 retention path (review M3-03, documented policy):
 *
 * <p><b>Owner:</b> platform operator (deployment config, not individual
 * teachers). <b>Policy:</b> roster entries are invitations/audit records.
 * PENDING invitations never expire automatically (a teacher may withdraw via
 * revoke). Terminal entries (REVOKED/DECLINED) — invitations that were
 * withdrawn or declined, and any sensitive ciphertext they carry (DOB,
 * phones, raw email) — are deleted {@code quizopia.roster.retention.days}
 * days after their last update. The canonical matching email is deleted with
 * the row. LINKED entries are live state and are never touched here.
     * <p><b>Consent consequence:</b> deleting a DECLINED row removes that
     * classroom's standing opt-out for the address — after the retention
     * window a teacher re-invitation CAN auto-link again. The opt-out window
     * therefore equals the retention window; operators choosing a short
     * retention accept shorter consent memory.
 *
 * <p><b>Default:</b> {@code 0} = cleanup DISABLED — no implicit data
 * destruction until an operator explicitly opts in per environment. Set
 * e.g. {@code QUIZOPIA_ROSTER_RETENTION_DAYS=365} to enable the daily job.
 */
@Component
public class ClassroomRosterRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClassroomRosterRetentionScheduler.class);

    private final ClassroomRosterEntryRepository rosterRepository;
    private final int retentionDays;

    public ClassroomRosterRetentionScheduler(ClassroomRosterEntryRepository rosterRepository,
            @Value("${quizopia.roster.retention.days:0}") int retentionDays) {
        this.rosterRepository = rosterRepository;
        this.retentionDays = retentionDays;
    }

    /**
     * Daily; no-op unless retention is configured. Transactional: the
     * @Modifying delete requires a transaction boundary (review R2-SEC P2).
     */
    @Scheduled(fixedDelay = 24 * 3600 * 1000L, initialDelay = 24 * 3600 * 1000L)
    @org.springframework.transaction.annotation.Transactional
    public void purgeTerminalEntries() {
        if (retentionDays <= 0) {
            return;
        }
        java.time.Instant cutoff = java.time.Instant.now().minus(
                java.time.Duration.ofDays(retentionDays));
        int removed = rosterRepository.deleteTerminalOlderThan(cutoff);
        if (removed > 0) {
            log.info("Roster retention: deleted {} terminal entries older than {}",
                    removed, cutoff);
        }
    }
}
