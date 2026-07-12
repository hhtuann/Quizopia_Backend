package com.quizopia.backend.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response for {@code POST /api/exams/{examId}/publish} (HTTP 200). Carries NO answer data
 * (no answerKey, no isCorrect) — only the published version's identity, points, and counts.
 */
public record PublishedExamSummary(
        Long examId,
        Integer versionNumber,
        String status,
        Instant publishedAt,
        BigDecimal totalPoints,
        Integer questionCount,
        Integer durationMinutes
) {
}
