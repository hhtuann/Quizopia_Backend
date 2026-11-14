package com.quizopia.backend.media.webhook;

import java.time.Instant;

/**
 * Verified, normalised LiveKit webhook event (P4-B4, issue #87). Produced
 * only after signature + body-hash + freshness checks pass. The
 * {@code dedupeKey} is a deterministic sha256 over the identity-defining
 * fields so replays are caught by the ledger table.
 */
public record LiveKitWebhookEvent(
        String event,
        String roomName,
        String participantIdentity,
        String participantState,
        String trackSid,
        String trackType,
        String trackSource,
        Instant timestamp,
        String dedupeKey
) {

    public boolean isCameraTrackPublished() {
        return "track_published".equals(event)
                && "VIDEO".equals(trackType)
                && "CAMERA".equals(trackSource);
    }

    /** Events that terminate an open camera connection, whatever the cause. */
    public boolean isCameraTermination() {
        return "track_unpublished".equals(event)
                || "participant_left".equals(event)
                || "participant_connection_aborted".equals(event)
                || "participant_disconnected".equals(event);
    }
}
