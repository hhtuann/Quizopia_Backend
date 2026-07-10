package com.hhtuann.backend.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateExamRequest(
        @NotNull @Positive Long subjectId,
        @Positive Long purposeId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description
) {}
