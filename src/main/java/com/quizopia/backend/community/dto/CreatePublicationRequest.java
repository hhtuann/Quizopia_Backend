package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/community/publications body (ADR-006 §4). {@code tags} are
 * normalized (trim + lower-case) and pattern-checked in the service;
 * {@code reusePolicy} defaults to NO_FORK.
 */
public record CreatePublicationRequest(
        @NotNull Long examId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 500) String summary,
        Long categoryId,
        List<String> tags, // ≤5 DISTINCT enforced in the service (raw dupes collapse)
        @Size(max = 80) String authorAlias,
        String reusePolicy) {
}
