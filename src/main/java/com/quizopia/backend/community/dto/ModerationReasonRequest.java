package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.Size;

/**
 * POST …/hide|restore and …/reports/{id}/dismiss|resolve bodies
 * (ADR-006 §7). Hide/restore take a MANDATORY {@code reason}
 * (1..500 after trim, enforced in the service); dismiss/resolve take an
 * OPTIONAL {@code note} (≤500) — the audit row always carries a
 * non-blank reason (the note, or the action verb when no note was
 * given), satisfying the V27 {@code reason NOT NULL} contract.
 */
public record ModerationReasonRequest(
        @Size(max = 500) String reason,
        @Size(max = 500) String note) {
}
