package com.hhtuann.backend.security.token;

/**
 * Generates opaque refresh tokens.
 *
 * <p>Tokens are produced from a cryptographically secure random source, carry
 * at least 256 bits of entropy, and never embed user identity or any guessable
 * data. The plaintext token exists only on the client and during the single
 * request that consumes it; the database stores only a hash (see
 * {@link RefreshTokenHasher}).
 *
 * <p>Implementations must never log the generated token.
 */
public interface RefreshTokenGenerator {

    /**
     * Generates a fresh opaque refresh token.
     *
     * @return a URL-safe, unpadded token string with at least 256 bits of entropy
     */
    String generate();
}
