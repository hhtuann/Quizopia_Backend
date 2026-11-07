package com.quizopia.backend.community.domain.model;

/**
 * Moderation state of a publication (ADR-006 §4). HIDDEN is set only by
 * moderators (#42); it blocks publish (409 COMMUNITY_MODERATION_HIDDEN)
 * and survives unpublish/republish. #40 never writes HIDDEN.
 */
public enum CommunityModerationState {
    VISIBLE,
    HIDDEN
}
