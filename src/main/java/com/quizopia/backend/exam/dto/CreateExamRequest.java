package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/exams}. The exam is owned by the
 * authenticated user; {@code subjectId}/{@code purposeId} are optional
 * global-catalog metadata.
 */
public record CreateExamRequest(
        Long subjectId,
        @Positive Long purposeId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description
) {}
