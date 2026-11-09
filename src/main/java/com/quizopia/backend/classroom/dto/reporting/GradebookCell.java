package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;

/**
 * CLASS-M3 (issue #54): one (student, session) matrix cell.
 *
 * <p>Status values: {@code UPCOMING}, {@code DRAFT}, {@code CANCELLED},
 * {@code NOT_HELD} (window empty — session expired without opening),
 * {@code NOT_IN_COHORT} (membership never overlapped the taking window),
 * {@code NOT_ATTEMPTED} (in cohort, zero attempts — never a zero score),
 * {@code IN_PROGRESS} (only an unfinished attempt), {@code PENDING_GRADING}
 * (submitted, no persisted Grade yet), {@code SCORED} (best graded result:
 * score/maxScore/percentage/bestAttemptId populated; attemptCount counts ALL
 * attempts of this (session, user) — including unfinished ones).</p>
 */
public record GradebookCell(
        String status,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage,
        Long bestAttemptId,
        long attemptCount,
        String gradeStatus) {

    public static GradebookCell state(String status) {
        return new GradebookCell(status, null, null, null, null, 0L, null);
    }
}
