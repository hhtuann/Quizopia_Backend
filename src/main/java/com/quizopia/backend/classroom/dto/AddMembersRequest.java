package com.quizopia.backend.classroom.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record AddMembersRequest(
        @NotNull @NotEmpty List<@Positive Long> studentProfileIds
) {
}
