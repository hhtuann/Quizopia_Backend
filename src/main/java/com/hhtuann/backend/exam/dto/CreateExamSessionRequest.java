package com.hhtuann.backend.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateExamSessionRequest(
        @NotNull @Positive Long examId,
        @NotNull @Positive Integer examVersionNumber,
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 255) String title,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull @Positive Integer maxAttempts
) {
}
