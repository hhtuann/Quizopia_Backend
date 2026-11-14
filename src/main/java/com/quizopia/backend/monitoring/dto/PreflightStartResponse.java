package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of POST /api/exam-sessions/{sessionId}/monitoring/preflights
 * (P4-B2, issue #85). The media token is a short-lived LiveKit JWT — the
 * response is always served {@code Cache-Control: no-store} and the token is
 * never logged. A readiness lease is NOT part of this payload: it is minted
 * only by the verify endpoint after the backend has confirmed the published
 * camera track.
 *
 * @param preflightId         opaque preflight id (also the verify path id)
 * @param mediaToken          student publisher token (camera-only publish)
 * @param mediaTokenExpiresAt token expiry (mediaTokenTtl, default 120s)
 * @param mediaIdentity       opaque per-preflight LiveKit identity
 * @param wsUrl               LiveKit signaling URL
 * @param roomScope           always {@code "session"} (one room per session)
 * @param cameraRequired      derived flag (= monitoringEnabled)
 * @param expiresAt           preflight lifetime end (preflightTtl, 5m)
 */
public record PreflightStartResponse(
        UUID preflightId,
        String mediaToken,
        Instant mediaTokenExpiresAt,
        String mediaIdentity,
        String wsUrl,
        String roomScope,
        boolean cameraRequired,
        Instant expiresAt) {

    public PreflightStartResponse {
        // Jackson
    }
}
