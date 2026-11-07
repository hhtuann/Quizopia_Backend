package com.quizopia.backend.classroom.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Request body for {@code POST /api/classrooms/{id}/members}: bulk add of
 * platform users (by user id) with partial-success classification.
 */
public record AddMembersRequest(
        @NotNull @NotEmpty List<@Positive Long> userIds
) {
}
