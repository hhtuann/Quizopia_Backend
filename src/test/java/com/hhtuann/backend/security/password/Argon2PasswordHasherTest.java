package com.hhtuann.backend.security.password;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Argon2PasswordHasher}.
 *
 * <p>These tests are intentionally lightweight: Argon2id is deliberately slow,
 * so each test performs only the minimum number of hash/verify operations
 * needed. Raw passwords and hashes are never printed.
 */
class Argon2PasswordHasherTest {

    private PasswordHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new Argon2PasswordHasher();
    }

    @Test
    void hashThenMatchesSucceedsForCorrectPassword() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
    }

    @Test
    void matchesReturnsFalseForWrongPassword() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hasher.matches("a-different-password", hash)).isFalse();
    }

    @Test
    void hashingTheSamePasswordTwiceProducesDifferentHashesBecauseOfSalt() {
        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");

        assertThat(first).isNotEqualTo(second);
        // Both hashes must still verify the same raw password.
        assertThat(hasher.matches("same-password", first)).isTrue();
        assertThat(hasher.matches("same-password", second)).isTrue();
    }

    @Test
    void matchesReturnsFalseWhenEitherArgumentIsNull() {
        String hash = hasher.hash("some-password");

        assertThat(hasher.matches(null, hash)).isFalse();
        assertThat(hasher.matches("some-password", null)).isFalse();
    }

    @Test
    void needsRehashReturnsFalseForHashProducedWithCurrentParameters() {
        String hash = hasher.hash("some-password");

        // A hash just produced with the current encoder needs no rehash.
        assertThat(hasher.needsRehash(hash)).isFalse();
    }

    @Test
    void hashRejectsNullRawPassword() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void needsRehashRejectsNullHash() {
        assertThatThrownBy(() -> hasher.needsRehash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void producedHashIsArgon2idEncoded() {
        String hash = hasher.hash("some-password");

        // Spring Security's Argon2PasswordEncoder uses the Argon2id variant,
        // encoded with the "$argon2id$" MCF prefix.
        assertThat(hash).startsWith("$argon2id$");
    }
}
