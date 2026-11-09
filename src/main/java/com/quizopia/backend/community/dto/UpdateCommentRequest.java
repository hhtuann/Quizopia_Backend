package com.quizopia.backend.community.dto;

/**
 * PATCH /api/community/comments/{commentId} body (ADR-007 §5, #66).
 * Bounds checked in the service after admission (COMMUNITY_VALIDATION_ERROR).
 */
public record UpdateCommentRequest(
        String body) {
}
