package com.quizopia.backend.security.token;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * {@link RefreshTokenGenerator} backed by {@link SecureRandom}.
 *
 * <p>Each token is 32 random bytes (256 bits) encoded as unpadded URL-safe
 * Base64, yielding a 43-character string. The token carries no user identity
 * and is never logged.
 */
@Component
public class SecureRefreshTokenGenerator implements RefreshTokenGenerator {

    /**
     * Number of random bytes per token: 32 bytes = 256 bits of entropy.
     */
    static final int TOKEN_BYTE_LENGTH = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom secureRandom;

    /**
     * Creates a generator using a default {@link SecureRandom}.
     */
    public SecureRefreshTokenGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a generator with the provided {@link SecureRandom}. The argument
     * is intended for tests that need a deterministic source.
     *
     * @param secureRandom the secure random source; must not be {@code null}
     */
    SecureRefreshTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
