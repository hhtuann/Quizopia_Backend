package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Body of {@code PUT /api/community/publications/{id}/bookmark}
 * (ADR-006 §7, issue #45): 201 on first save, 200 on the idempotent
 * repeat, with the same minimal shape either way — the shelf entry
 * itself is only ever read from {@code GET /me/bookmarks}.
 */
public record CommunityBookmarkResponse(
        Long publicationId,
        Instant savedAt) {
}
