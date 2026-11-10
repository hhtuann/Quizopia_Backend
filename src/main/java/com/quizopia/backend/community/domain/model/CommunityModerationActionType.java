package com.quizopia.backend.community.domain.model;

/**
 * {@code community_moderation_actions.action} catalog (ADR-006 §3 V27
 * CHECK, widened by ADR-007 §3 V31). MODERATOR_GRANTED /
 * MODERATOR_REVOKED rows are written only by the operator runbook (§5)
 * with the dedicated operator account as actor; the application
 * writes the other eight. COMMENT_* rows (ADR-007 §5, #66) never
 * carry from/to_status, and their from/to_moderation_state record
 * VISIBLE↔HIDDEN transitions only — DELETED never appears (owner
 * deletes are not moderation actions; refused hide/restore attempts
 * never audit).
 */
public enum CommunityModerationActionType {
    HIDE,
    RESTORE,
    REPORT_DISMISS,
    REPORT_RESOLVE,
    MODERATOR_GRANTED,
    MODERATOR_REVOKED,
    COMMENT_HIDE,
    COMMENT_RESTORE,
    COMMENT_REPORT_DISMISS,
    COMMENT_REPORT_RESOLVE
}
