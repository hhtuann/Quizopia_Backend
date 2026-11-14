package com.quizopia.backend.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response of POST …/monitoring/preflights/{preflightId}/verify (P4-B2,
 * issue #85). Two states: {@code NOT_PUBLISHED} (client keeps polling —
 * absent fields omitted) or {@code READY} carrying the one-time readiness
 * lease the start request must echo back. The RAW lease appears exactly
 * here; the backend persists only its sha256 hash.
 *
 * @param state                 {@code NOT_PUBLISHED} or {@code READY}
 * @param readinessLease        raw one-time lease (READY only)
 * @param readinessExpiresAt    lease expiry (readinessLeaseTtl, 90s; READY only)
 * @param verifiedCameraTrackSid sid of the confirmed camera track (READY only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreflightVerifyResponse(
        String state,
        String readinessLease,
        Instant readinessExpiresAt,
        String verifiedCameraTrackSid) {

    public static final String STATE_NOT_PUBLISHED = "NOT_PUBLISHED";
    public static final String STATE_READY = "READY";

    public static PreflightVerifyResponse notPublished() {
        return new PreflightVerifyResponse(STATE_NOT_PUBLISHED, null, null, null);
    }

    public static PreflightVerifyResponse ready(String readinessLease,
            Instant readinessExpiresAt, String verifiedCameraTrackSid) {
        return new PreflightVerifyResponse(STATE_READY, readinessLease,
                readinessExpiresAt, verifiedCameraTrackSid);
    }

    public PreflightVerifyResponse {
        // Jackson
    }
}
