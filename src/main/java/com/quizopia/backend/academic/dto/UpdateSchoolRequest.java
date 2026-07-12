package com.quizopia.backend.academic.dto;

import jakarta.validation.constraints.Size;

public record UpdateSchoolRequest(
        @Size(max = 255) String name,
        @Size(max = 500) String address
) {
}
