package com.quizopia.backend.classroom.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Identity-M3: entry point for verified-email roster reconciliation.
 *
 * <p>Called AFTER an email-ownership proof commits (OTP consume, Google
 * login provisioning, Google link). Idempotent: a second call finds no
 * PENDING entries. Failures are logged and swallowed by CALLERS where they
 * must not fail the verification flow itself; this facade itself never
 * throws for a missing user — it simply does nothing.
 */
@Service
public class RosterReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(RosterReconciliationService.class);

    private final ClassroomRosterService rosterService;

    public RosterReconciliationService(ClassroomRosterService rosterService) {
        this.rosterService = rosterService;
    }

    /** Links pending roster entries for the user's verified email. */
    public void reconcile(Long userId, String verifiedEmail) {
        if (userId == null || verifiedEmail == null || verifiedEmail.isBlank()) {
            return;
        }
        log.debug("Roster reconciliation for user {}", userId);
        rosterService.reconcileEntriesForUser(userId, verifiedEmail);
    }
}
