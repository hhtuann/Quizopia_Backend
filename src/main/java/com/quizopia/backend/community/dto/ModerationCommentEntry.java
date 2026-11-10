package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Moderator hide/restore response (ADR-007 §5, #66; Rev 4 / #72): the
 * acted-on comment with its resulting moderation state. The body is
 * moderator visibility (retained while HIDDEN, empty once DELETED).
 * {@code authorName} is the author's CURRENT account display name.
 */
public record ModerationCommentEntry(
        Long commentId,
        Long publicationId,
        String authorName,
        String body,
        String state,
        Instant createdAt,
        Instant editedAt,
        Instant updatedAt) {
}
