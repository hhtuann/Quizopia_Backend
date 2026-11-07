package com.quizopia.backend.community.domain.model;

/**
 * {@code community_moderation_actions.action} catalog (ADR-006 §3 V27
 * CHECK constraint). MODERATOR_GRANTED / MODERATOR_REVOKED rows are
 * written only by the operator runbook (§5) with the dedicated operator
 * account as actor; the application writes the other four.
 */
public enum CommunityModerationActionType {
    HIDE,
    RESTORE,
    REPORT_DISMISS,
    REPORT_RESOLVE,
    MODERATOR_GRANTED,
    MODERATOR_REVOKED
}
