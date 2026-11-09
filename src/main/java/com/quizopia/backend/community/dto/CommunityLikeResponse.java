package com.quizopia.backend.community.dto;

/**
 * Body of {@code PUT/DELETE /api/community/publications/{id}/like}
 * (ADR-007 §5, issue #65): 201 on first like / 200 idempotent re-PUT,
 * 200 always on DELETE, with the recomputed aggregate either way —
 * {@code {likeCount, liked}} exactly.
 */
public record CommunityLikeResponse(
        long likeCount,
        boolean liked) {
}
