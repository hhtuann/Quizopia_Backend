package com.quizopia.backend.attempt.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Day 8 session result row (teacher/admin paginated list). One row per student — the BEST result.
 * No answer payload, no GradeItem details, no answer key.
 */
public record SessionResultItem(
        Long studentId,
        String studentCode,
        String displayName,
        Long bestAttemptId,
        int attemptCount,
        Instant submittedAt,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage,
        String gradeStatus) {
}
