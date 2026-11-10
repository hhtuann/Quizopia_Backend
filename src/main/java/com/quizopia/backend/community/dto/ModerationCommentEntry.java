package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Moderator hide/restore response (ADR-007 §5, #66): the acted-on
 * comment with its resulting moderation state. The body is moderator
 * visibility (retained while HIDDEN, empty once DELETED).
 */
public record ModerationCommentEntry(
        Long commentId,
        Long publicationId,
        String authorAlias,
        String body,
        String state,
        Instant createdAt,
        Instant editedAt,
        Instant updatedAt) {
}
