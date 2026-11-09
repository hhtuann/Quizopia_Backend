package com.quizopia.backend.community.domain.model;

/**
 * {@code community_comments.state} catalog (ADR-007 §3 V31 CHECK):
 * VISIBLE is the only publicly listed state; HIDDEN is the moderator
 * hide (body retained server-side for review, absent publicly, present
 * in the moderator queue); DELETED is the owner soft-delete tombstone
 * — body cleared, never listed, never restored.
 */
public enum CommunityCommentState {
    VISIBLE,
    HIDDEN,
    DELETED
}
