package com.hhtuann.backend.question.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Question summary with current-version content, used in
 * {@code GET /api/question-banks/{bankId}/questions}.
 * Never exposes answerKey, expectedAnswer, isCorrect, or internal metadata.
 */
public record QuestionSummary(
        Long id,
        String code,
        Integer currentVersionNumber,
        String questionType,
        String content,
        String difficulty,
        BigDecimal defaultPoints,
        String status,
        Instant createdAt
) {}
