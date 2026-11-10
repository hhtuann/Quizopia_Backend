package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * One item of the public comments page (ADR-007 §5, #66; Rev 4 / #72):
 * the fifth public GET, anonymous-readable. Items are VISIBLE comments
 * ONLY ({@code state} implicit — no tombstone placeholders, moderation
 * opacity per ADR-007 §3).
 *
 * <p><b>Author identity (ADR-007 Rev 4)</b>: {@code authorName} is the
 * author's CURRENT account profile display name — the single
 * owner-approved public profile field, resolved server-side from
 * {@code users.display_name} at read time (renames are reflected
 * immediately; duplicates are allowed; a privacy-safe generic fallback
 * replaces a missing/blank name — never username, email or any id).
 *
 * <p><b>Ownership</b>: {@code isMine} is computed server-side from the
 * authenticated caller identity against {@code author_user_id} — never
 * from name text. Anonymous callers get {@code false} on every item
 * (no viewer state, no internal author ids anywhere in this DTO).
 */
public record PublicCommentEntry(
        Long commentId,
        String authorName,
        String body,
        Instant createdAt,
        Instant editedAt,
        boolean isMine) {
}
