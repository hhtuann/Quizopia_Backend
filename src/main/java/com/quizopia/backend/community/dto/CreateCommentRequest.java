package com.quizopia.backend.community.dto;

/**
 * POST /api/community/publications/{id}/comments body (ADR-007 §5,
 * #66). No bean-validation constraints: the body and alias bounds are
 * checked in {@code CommunityCommentService} with the community error
 * envelope, AFTER admission + permission + the §4 visibility 404 (the
 * module-wide fixed order).
 */
public record CreateCommentRequest(
        String body,
        String alias) {
}
