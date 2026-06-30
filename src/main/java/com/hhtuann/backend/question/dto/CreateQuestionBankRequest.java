package com.hhtuann.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/question-banks}.
 * Client must NOT send schoolId or ownerTeacherId — the backend resolves them
 * from the authenticated teacher profile.
 */
public record CreateQuestionBankRequest(
        @NotBlank @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull Long subjectId
) {}
