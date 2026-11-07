package com.quizopia.backend.community.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PATCH /api/community/publications/{id} body (ADR-006 §4). The PATCHable
 * set is exactly {@code title, summary, categoryId, tags (replace-set),
 * authorAlias, reusePolicy}; {@code null} means "leave unchanged".
 * {@code examId} and the publisher are immutable — no field exists here
 * for them. {@code expectedVersion} is required; a mismatch is 409
 * COMMUNITY_CONCURRENT_MODIFICATION.
 */
public record UpdatePublicationRequest(
        @NotNull Integer expectedVersion,
        @Size(max = 255) String title,
        @Size(max = 500) String summary,
        Long categoryId,
        List<String> tags, // ≤5 DISTINCT enforced in the service (raw dupes collapse)
        @Size(max = 80) String authorAlias,
        String reusePolicy) {
}
