package com.quizopia.backend.security.encryption;

/**
 * Encrypts and decrypts sensitive personal data (for example phone numbers and
 * national identifiers) using authenticated encryption.
 *
 * <p>Ciphertext strings carry a version prefix (for example {@code v1:}) so the
 * format can evolve and so unsupported versions are rejected explicitly. The
 * plaintext, the ciphertext and the key must never be logged.
 *
 * <p>Implementations fail fast: {@code null} inputs and unsupported ciphertext
 * versions are rejected with a clear exception rather than silently producing a
 * value.
 */
public interface SensitiveDataEncryptor {

    /**
     * Encrypts the given plaintext.
     *
     * @param plaintext the value to encrypt; must not be {@code null} (the empty
     *                  string is permitted)
     * @return versioned ciphertext with a fresh random nonce
     */
    String encrypt(String plaintext);

    /**
     * Decrypts versioned ciphertext previously produced by {@link #encrypt}.
     *
     * @param ciphertext the versioned ciphertext; must not be {@code null}
     * @return the original plaintext
     * @throws IllegalArgumentException if the ciphertext prefix is unsupported
     * @throws IllegalStateException    if the ciphertext is corrupted or tampered
     *                                  with (fails GCM authentication)
     */
    String decrypt(String ciphertext);
}
