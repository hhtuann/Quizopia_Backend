package com.quizopia.backend.media.roomservice;

import java.util.List;

/**
 * Minimal projection of a LiveKit {@code ParticipantInfo} (Twirp JSON,
 * protojson field names). Only the fields the camera gate needs.
 */
public record LkParticipant(
        String identity,
        String state,
        List<LkTrack> tracks
) {

    /** True when the participant is connected with an active camera video track. */
    public boolean hasActiveCamera() {
        return tracks != null && tracks.stream()
                .anyMatch(t -> "VIDEO".equals(t.type()) && "CAMERA".equals(t.source()) && !t.muted());
    }
}
