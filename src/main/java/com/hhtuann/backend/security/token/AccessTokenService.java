package com.hhtuann.backend.security.token;

import java.util.List;

/**
 * Issues short-lived JWT access tokens.
 *
 * <p>This service is responsible only for <em>issuing</em> access tokens.
 * Verification (signature, expiration, issuer, audience, token version) is the
 * responsibility of the {@code JwtDecoder} bean and the Spring Security OAuth2
 * resource server, not this interface.
 *
 * <p>Issued tokens must never contain email, phone, national identifier,
 * password hash, refresh token or permission data. They carry only the identity
 * claims required to authenticate a request: subject (user id), username, role
 * codes, token version, plus the standard {@code iss}/{@code aud}/{@code iat}/
 * {@code exp}/{@code jti} claims.
 */
public interface AccessTokenService {

    /**
     * Issues a new access token for the given principal.
     *
     * @param userId          the user identifier; becomes the {@code sub} claim as a string
     * @param username        the username; included as the {@code username} claim
     * @param activeRoleCodes the user's currently effective role codes; included as {@code roles}
     * @param tokenVersion    the user's current token version; included as {@code token_version}
     * @return the issued token together with its expiry instant
     */
    IssuedAccessToken issue(Long userId, String username, List<String> activeRoleCodes, int tokenVersion);
}
