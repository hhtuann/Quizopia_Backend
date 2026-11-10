package com.quizopia.backend.community.dto;

import java.time.Instant;

/**
 * The comment block of a moderator queue item (ADR-007 §5, #66):
 * exactly the content a moderator needs to judge the report — body
 * (retained server-side even when HIDDEN), the author's alias SNAPSHOT
 * (never the author's user id — comment ids and aliases are public
 * data, the account behind them is not) and the creation time.
 */
public record ModerationCommentSummary(
        String body,
        String authorAlias,
        Instant createdAt) {
}
