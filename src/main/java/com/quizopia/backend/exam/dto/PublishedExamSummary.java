package com.quizopia.backend.exam.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response for {@code POST /api/exams/{examId}/publish} (HTTP 200). Carries NO
 * answer data (no answerKey, no isCorrect) — only the exam's published
 * identity, points, and counts.
 */
public record PublishedExamSummary(
        Long examId,
        String status,
        Instant publishedAt,
        BigDecimal totalPoints,
        Integer questionCount,
        Integer durationMinutes
) {
}
