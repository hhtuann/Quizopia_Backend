package com.quizopia.backend.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/question-banks}.
 * Client must NOT send schoolId or ownerTeacherId — the backend resolves
 * ownership from the authenticated user and workspace.
 *
 * <p>Public platform foundation (issue #1 A3):
 * <ul>
 *   <li>{@code workspaceId} optional — defaults to the caller's organization
 *       workspace when they have a teacher profile, else their personal
 *       workspace. When supplied, the caller needs an active membership with
 *       content-editor rights (OWNER/ADMIN/EDITOR).</li>
 *   <li>{@code subjectId} optional for PERSONAL workspaces; required and
 *       school-matched for ORGANIZATION workspaces (legacy academic rule).</li>
 * </ul>
 */
public record CreateQuestionBankRequest(
        Long workspaceId,
        @Size(max = 80) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        Long subjectId
) {}
