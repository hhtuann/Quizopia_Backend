package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * The comment block of a comment-reports queue item (ADR-007 §5, #66;
 * Rev 4 / #72). {@code authorName} is the author's CURRENT account
 * display name (moderator privilege — bare internal user ids appear
 * elsewhere in the queue entry by design, never publicly).
 */
public record ModerationCommentSummary(
        String body,
        String authorName,
        Instant createdAt) {
}
