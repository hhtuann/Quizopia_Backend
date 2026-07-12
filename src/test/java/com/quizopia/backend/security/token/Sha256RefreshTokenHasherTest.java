package com.quizopia.backend.security.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Sha256RefreshTokenHasher}.
 */
class Sha256RefreshTokenHasherTest {

    /**
     * Matches the {@code refresh_sessions.token_hash} CHECK constraint.
     */
    private static final Pattern LOWER_HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    private RefreshTokenHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new Sha256RefreshTokenHasher();
    }

    @Test
    void hashIsDeterministicForTheSameToken() {
        String token = "some-opaque-refresh-token";

        String first = hasher.hash(token);
        String second = hasher.hash(token);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void hashIsExactly64LowercaseHexCharacters() {
        String hash = hasher.hash("some-opaque-refresh-token");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches(LOWER_HEX_64);
    }

    @Test
    void differentTokensProduceDifferentHashes() {
        String a = hasher.hash("token-one");
        String b = hasher.hash("token-two");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashMatchesKnownSha256Vector() {
        // SHA-256("abc") in lowercase hex.
        String hash = hasher.hash("abc");

        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashRejectsNullToken() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(NullPointerException.class);
    }
}
