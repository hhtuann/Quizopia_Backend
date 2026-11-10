package com.quizopia.backend.community.dto;

/**
 * POST /api/community/publications/{id}/comments body (ADR-007 §5,
 * #66; ADR-007 Rev 4 / #72): body ONLY. The author identity is derived
 * exclusively from the authenticated user — a legacy client still
 * sending an {@code alias} field is safely ignored (unknown JSON
 * properties are not deserialization failures); it can never override
 * the displayed author name or impersonate another user.
 *
 * <p>No bean-validation constraints: the body bounds are checked in
 * {@code CommunityCommentService} with the community error envelope,
 * AFTER admission + permission + the §4 visibility 404 (the
 * module-wide fixed order).
 */
public record CreateCommentRequest(
        String body) {
}
