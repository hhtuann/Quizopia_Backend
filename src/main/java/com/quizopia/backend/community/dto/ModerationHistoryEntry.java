package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * Per-publication moderation history row (ADR-006 §7): audit rows
 * newest-first, actor rendered as a bare internal user id (moderator
 * privilege, §5) — no username/email/phone.
 */
public record ModerationHistoryEntry(
        Long id,
        Long actorUserId,
        String action,
        Long reportId,
        String fromStatus,
        String toStatus,
        String fromModerationState,
        String toModerationState,
        String reason,
        Instant createdAt) {
}
