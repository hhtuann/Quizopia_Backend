package com.quizopia.backend.question.dto;

import java.time.Instant;

/** List item in {@code GET /api/question-banks/my} response. */
public record QuestionBankListItem(
        Long id,
        String code,
        String name,
        String description,
        SubjectSummary subject,
        long questionCount,
        String status,
        Instant createdAt
) {}
