package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * One item of the public comments page (ADR-007 §5, #66): the fifth
 * public GET, anonymous-readable. Items are VISIBLE comments ONLY
 * ({@code state} implicit — no tombstone placeholders, moderation
 * opacity per ADR-007 §3); the author is identified by the alias
 * snapshot alone — no user ids, no emails, no profile links.
 */
public record PublicCommentEntry(
        Long commentId,
        String authorAlias,
        String body,
        Instant createdAt,
        Instant editedAt) {
}
