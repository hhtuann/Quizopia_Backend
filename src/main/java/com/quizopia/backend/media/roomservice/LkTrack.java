package com.quizopia.backend.media.roomservice;

/**
 * Minimal projection of a LiveKit {@code Track} (protojson: type/source are
 * enum names such as {@code VIDEO}/{@code CAMERA}; protojson may also emit
 * numeric enum values, which the gateway normalises to names).
 */
public record LkTrack(
        String sid,
        String type,
        String source,
        boolean muted
) {
}
