package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/exams}.
 *
 * <p>Public platform foundation (issue #1 A3): {@code workspaceId} is optional —
 * defaults to the caller's organization workspace when they hold a teacher
 * profile, else their personal workspace. {@code subjectId} is required for
 * ORGANIZATION workspaces (legacy academic rule) and unsupported for PERSONAL
 * workspaces.
 */
public record CreateExamRequest(
        Long workspaceId,
        Long subjectId,
        @Positive Long purposeId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description
) {}
