package com.quizopia.backend.community.dto;

/**
 * GET /api/community/moderation/grant response (ADR-006 §7): the FE
 * console gate. 200 {@code {"granted":true}} when the caller holds the
 * effective COMMUNITY_MODERATION permission and is ACTIVE; callers
 * without the grant never reach this body (403).
 */
public record ModerationGrantResponse(boolean granted) {
}
