package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Comment DTO for the owner create/PATCH responses (ADR-007 §5, #66;
 * Rev 4 / #72). Public identity is the author's CURRENT account
 * display name — no user id anywhere. {@code editedAt} is null until
 * the first owner PATCH.
 */
public record CommentResponse(
        Long commentId,
        Long publicationId,
        String authorName,
        String body,
        Instant createdAt,
        Instant editedAt) {
}
