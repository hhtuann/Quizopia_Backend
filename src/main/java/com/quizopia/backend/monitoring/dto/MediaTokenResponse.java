package com.quizopia.backend.monitoring.dto;

import java.time.Instant;

/**
 * Response of the two media-token endpoints (P4-B, issues #85/#86):
 * POST /api/attempts/{attemptId}/monitoring/media-token (student renewal —
 * same identity as the preflight) and GET /api/exam-sessions/{sessionId}/
 * monitoring/media-token (teacher viewer, identity {@code teacher-<userId>}).
 * Served {@code Cache-Control: no-store}; the token is never logged.
 *
 * @param mediaToken          short-lived LiveKit JWT (mediaTokenTtl, 120s)
 * @param mediaTokenExpiresAt token expiry instant
 * @param mediaIdentity       LiveKit participant identity of the token
 * @param wsUrl               LiveKit signaling URL
 * @param roomScope           always {@code "session"} (one room per session)
 */
public record MediaTokenResponse(
        String mediaToken,
        Instant mediaTokenExpiresAt,
        String mediaIdentity,
        String wsUrl,
        String roomScope) {

    public MediaTokenResponse {
        // Jackson
    }
}
