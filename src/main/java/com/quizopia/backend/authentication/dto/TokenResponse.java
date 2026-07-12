package com.quizopia.backend.authentication.dto;

import java.time.Instant;
import java.util.List;

/**
 * Access-token response returned by login and refresh. The refresh token is
 * delivered only via an HttpOnly cookie and is therefore never present here.
 *
 * @param accessToken     the serialized JWT
 * @param tokenType       always {@code Bearer}
 * @param expiresAt       when the access token expires
 * @param expiresInSeconds the access-token lifetime in seconds
 * @param roles           the user's currently effective role codes
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        long expiresInSeconds,
        List<String> roles
) {
}
