package com.hhtuann.backend.attempt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Day 8 attempt result response (student/teacher/admin). Reads persisted Grade + GradeItems only —
 * never re-grades, never reads the answer key. {@code isBest} is computed from the BEST comparator
 * against all submitted+graded attempts for the same (student, session).
 */
public record AttemptResultResponse(
        Long attemptId,
        Long examSessionId,
        String status,
        Instant submittedAt,
        Instant gradedAt,
        String gradeStatus,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage,
        boolean isBest,
        int attemptCount,
        List<QuestionResultView> questionResults) {
}
