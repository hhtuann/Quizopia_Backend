package com.quizopia.backend.monitoring.dto;

/**
 * Request body of the fire-and-forget POST monitoring endpoints
 * (preflight create / verify / attempt media-token / participant revoke).
 * Clients send {@code {"schemaVersion":1}} for forward compatibility; the
 * value is accepted and ignored — every mutating camera endpoint acts on
 * path variables and authenticated identity only.
 */
public record PreflightActionRequest(Integer schemaVersion) {

    public PreflightActionRequest {
        // Jackson: absent body → null record is fine at the controller too.
    }
}
