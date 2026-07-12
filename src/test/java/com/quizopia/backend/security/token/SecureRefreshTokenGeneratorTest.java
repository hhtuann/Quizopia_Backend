package com.quizopia.backend.security.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecureRefreshTokenGenerator}.
 */
class SecureRefreshTokenGeneratorTest {

    /**
     * Unpadded URL-safe Base64 alphabet.
     */
    private static final Pattern URL_SAFE_UNPADDED = Pattern.compile("^[A-Za-z0-9_-]+$");

    private RefreshTokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SecureRefreshTokenGenerator();
    }

    @Test
    void generatedTokenIsUrlSafeAndUnpadded() {
        String token = generator.generate();

        assertThat(token).matches(URL_SAFE_UNPADDED);
        assertThat(token).doesNotContain("=");
    }

    @Test
    void generatedTokenHasExpectedLengthFor256BitsOfEntropy() {
        // 32 bytes -> ceil(32 * 8 / 6) = 43 unpadded URL-safe Base64 chars.
        String token = generator.generate();

        assertThat(token).hasSize(43);
    }

    @Test
    void generatedTokensAreUniqueWithinALargeBatch() {
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String token = generator.generate();
            assertThat(seen.add(token))
                    .as("duplicate refresh token generated at iteration %d", i)
                    .isTrue();
        }
    }

    @RepeatedTest(5)
    void repeatedGenerationsDoNotCollide() {
        String a = generator.generate();
        String b = generator.generate();

        assertThat(a).isNotEqualTo(b);
    }
}
