package com.hhtuann.backend.question.dto;

import java.time.Instant;

/** Response for {@code POST /api/question-banks} (201 Created). */
public record QuestionBankResponse(
        Long id,
        String code,
        String name,
        String description,
        SubjectSummary subject,
        long questionCount,
        Instant createdAt
) {}
