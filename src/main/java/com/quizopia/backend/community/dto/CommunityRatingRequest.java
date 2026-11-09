package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/community/publications/{id}/rating}
 * (ADR-007 §5, issue #65). Bean validation guards shape only — the
 * 1..5 bounds are re-checked in {@code CommunitySocialService} with
 * COMMUNITY_VALIDATION_ERROR (community convention).
 */
public record CommunityRatingRequest(
        @NotNull Integer rating) {
}
