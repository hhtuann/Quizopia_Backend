package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CLASS-M3 (issue #54): one assigned session in the LEARNER's own-results
 * projection — strictly self-scoped (no other learner's data, no roster).
 * {@code resultStatus} mirrors the gradebook cell states
 * (UPCOMING/IN_PROGRESS/PENDING_GRADING/SCORED/NOT_ATTEMPTED/...);
 * start/resume/result navigation stays with the existing
 * {@code /sessions/{id}} eligibility landing (M2 rule).
 */
public record LearnerMyResultsItem(
        Long sessionId,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        String examTitle,
        String resultStatus,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage,
        Long bestAttemptId,
        long attemptCount) {
}
