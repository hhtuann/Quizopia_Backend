package com.quizopia.backend.security.token;

/**
 * Hashes opaque refresh tokens for storage and lookup.
 *
 * <p>Because refresh tokens already carry high entropy, a fast cryptographic
 * hash is appropriate (no slow password-hashing algorithm is needed). The
 * output must be a 64-character lowercase hexadecimal string so it satisfies
 * the {@code refresh_sessions.token_hash} CHECK constraint
 * ({@code ^[0-9a-f]{64}$}) and can be used as a unique lookup key.
 *
 * <p>Implementations must never log the plaintext token or its hash.
 */
public interface RefreshTokenHasher {

    /**
     * Hashes a refresh token to a 64-character lowercase hexadecimal string.
     *
     * @param token the plaintext opaque refresh token; must not be {@code null}
     * @return the SHA-256 hex digest, exactly 64 lowercase hex characters
     */
    String hash(String token);
}
