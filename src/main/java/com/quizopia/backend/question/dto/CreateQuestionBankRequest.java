package com.quizopia.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/question-banks}.
 * Ownership is resolved server-side from the authenticated user; the
 * optional {@code subjectId} references the global academic catalog.
 */
public record CreateQuestionBankRequest(
        @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        Long subjectId
) {}
