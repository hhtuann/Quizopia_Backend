package com.quizopia.backend.security.token;

import java.time.Instant;
import java.util.Objects;

/**
 * An access token issued by {@link AccessTokenService}, together with its
 * expiry instant.
 *
 * <p>This is an immutable value object. The serialized token value is sensitive
 * (it grants access for its lifetime) and is therefore deliberately excluded
 * from {@link #toString()} so that logging this object cannot leak the token.
 */
public final class IssuedAccessToken {

    private final String tokenValue;
    private final Instant expiresAt;

    /**
     * @param tokenValue the serialized JWT; must not be {@code null}
     * @param expiresAt  the instant at which the token expires; must not be {@code null}
     */
    public IssuedAccessToken(String tokenValue, Instant expiresAt) {
        this.tokenValue = Objects.requireNonNull(tokenValue, "tokenValue must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * @return the serialized JWT value
     */
    public String getTokenValue() {
        return tokenValue;
    }

    /**
     * @return the instant at which the token expires
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IssuedAccessToken that)) {
            return false;
        }
        return tokenValue.equals(that.tokenValue) && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenValue, expiresAt);
    }

    /**
     * Excludes the token value on purpose to avoid leaking it through logs.
     */
    @Override
    public String toString() {
        return "IssuedAccessToken{expiresAt=" + expiresAt + '}';
    }
}
