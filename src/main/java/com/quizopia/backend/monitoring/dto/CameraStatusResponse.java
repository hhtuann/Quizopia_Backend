package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response of GET /api/exam-sessions/{sessionId}/monitoring/camera-status
 * (P4-B4, issue #87) — the teacher camera grid's server-truth snapshot.
 * Nulls are rendered explicitly (JSON {@code null}) so clients can rely on
 * {@code === null} checks.
 *
 * <p>State derivation (first match wins):
 * <ul>
 *   <li>{@code REVOKED} — binding revoked by a teacher.</li>
 *   <li>{@code CONNECTED} — an open camera connection period exists.</li>
 *   <li>{@code RECONNECTING} — binding still active with an IN_PROGRESS
 *       attempt but no open connection (expected during brief drops).</li>
 *   <li>{@code IDLE} — everything else (unbound preflight, finished
 *       attempt, housekeeping-closed binding).</li>
 * </ul>
 */
public record CameraStatusResponse(
        String roomScope,
        List<Participant> participants) {

    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_RECONNECTING = "RECONNECTING";
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_REVOKED = "REVOKED";

    /**
     * @param mediaIdentity   opaque per-preflight LiveKit identity
     * @param userId          owning learner
     * @param displayName     users.display_name (may be null)
     * @param attemptId       bound attempt (null while unbound)
     * @param state           CONNECTED | RECONNECTING | IDLE | REVOKED
     * @param connectedSince  open connection's connectedAt (null when not CONNECTED)
     * @param lastVerifiedAt  last webhook/reconcile confirmation (null when never seen)
     */
    public record Participant(
            String mediaIdentity,
            Long userId,
            String displayName,
            Long attemptId,
            String state,
            Instant connectedSince,
            Instant lastVerifiedAt) {
    }

    public CameraStatusResponse {
        // Jackson
    }
}
