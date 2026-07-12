package com.quizopia.backend.academic.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AssignSchoolRequest(
        @NotNull @Positive Long schoolId
) {
}
