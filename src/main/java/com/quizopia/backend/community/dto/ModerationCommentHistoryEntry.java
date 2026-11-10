package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Per-comment moderation history row (ADR-007 §5, #66): audit rows
 * newest-first, unpaged (publication-history precedent). Actor renders
 * as a bare internal user id (moderator privilege) — no
 * username/email. {@code fromModerationState}/{@code toModerationState}
 * carry VISIBLE↔HIDDEN only; the status pair is never set on comment
 * rows (ADR-007 §3).
 */
public record ModerationCommentHistoryEntry(
        Long id,
        Long actorUserId,
        String action,
        Long commentId,
        Long commentReportId,
        String fromModerationState,
        String toModerationState,
        String reason,
        Instant createdAt) {
}
