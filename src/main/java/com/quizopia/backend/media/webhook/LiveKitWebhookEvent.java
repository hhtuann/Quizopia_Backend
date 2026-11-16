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
        String dedupeKey,
        String egressId,
        String egressStatus,
        long egressStartedAtNs,
        long egressEndedAtNs,
        String egressError,
        java.util.List<EgressFile> egressFiles
) {

    /** Egress {@code FileInfo} projection (P4-D). */
    public record EgressFile(String filename, long size, long durationNs,
            long startedAtNs, long endedAtNs) {
    }

    /** P4-D: egress lifecycle events carry the authoritative EgressInfo. */
    public boolean isEgressLifecycle() {
        return ("egress_started".equals(event) || "egress_ended".equals(event)
                || "egress_updated".equals(event)) && egressId != null;
    }

    public boolean isCameraTrackPublished() {
        return "track_published".equals(event)
                && "VIDEO".equals(trackType)
                && "CAMERA".equals(trackSource);
    }

    /** Compact constructor alias for camera-only construction (P4-B callers/tests). */
    public LiveKitWebhookEvent(String event, String roomName, String participantIdentity,
            String participantState, String trackSid, String trackType, String trackSource,
            Instant timestamp, String dedupeKey) {
        this(event, roomName, participantIdentity, participantState, trackSid, trackType,
                trackSource, timestamp, dedupeKey, null, null, 0, 0, null,
                java.util.List.of());
    }

    /** Events that terminate an open camera connection, whatever the cause. */
    public boolean isCameraTermination() {
        return "track_unpublished".equals(event)
                || "participant_left".equals(event)
                || "participant_connection_aborted".equals(event)
                || "participant_disconnected".equals(event);
    }
}
