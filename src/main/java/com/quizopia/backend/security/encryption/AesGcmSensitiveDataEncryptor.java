package com.quizopia.backend.security.encryption;

import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * AES-256-GCM implementation of {@link SensitiveDataEncryptor}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Transformation: {@code AES/GCM/NoPadding}.</li>
 *   <li>Key: 256-bit, read from {@code QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64}
 *       (Base64 of exactly 32 bytes). Construction fails fast with a clear
 *       message if the key is missing, malformed, or the wrong length.</li>
 *   <li>Nonce: a fresh random 12-byte nonce per encryption; never reused on
 *       purpose.</li>
 *   <li>Authentication tag: 128 bits.</li>
 * </ul>
 *
 * <p>Ciphertext format: {@code v1:<base64url(nonce || ciphertext || tag)>} where
 * the Base64 is URL-safe and unpadded. Decryption checks the {@code v1:} prefix
 * and rejects anything else. A single changed byte makes decryption fail the GCM
 * authentication check.
 *
 * <p>The key, plaintext and ciphertext are never logged.
 */
@Component
public class AesGcmSensitiveDataEncryptor implements SensitiveDataEncryptor {

    /**
     * AES/GCM/NoPadding cipher transformation.
     */
    static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * Required AES key length in bytes (256-bit).
     */
    static final int KEY_LENGTH_BYTES = 32;

    /**
     * Fresh random nonce length in bytes per NIST SP 800-38D recommendations.
     */
    static final int NONCE_LENGTH_BYTES = 12;

    /**
     * GCM authentication tag length in bits.
     */
    static final int TAG_LENGTH_BITS = 128;

    /**
     * Ciphertext version prefix. The V5 schema CHECK constraint also requires
     * stored ciphertext to start with this prefix.
     */
    static final String VERSION_PREFIX = "v1:";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    /**
     * Spring-managed constructor reading the key from configuration. Explicitly
     * annotated so Spring prefers it over the test-only constructor below.
     *
     * @param properties the security configuration supplying the Base64 key
     */
    @Autowired
    public AesGcmSensitiveDataEncryptor(SecurityProperties properties) {
        this(properties.getEncryption().getKeyBase64());
    }

    /**
     * Constructor used for testing with a raw Base64 key.
     *
     * @param keyBase64 Base64 of exactly 32 bytes
     */
    AesGcmSensitiveDataEncryptor(String keyBase64) {
        Objects.requireNonNull(keyBase64, "encryption key must not be null");
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64 is not valid Base64", ex);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "QUIZOPIA_DATA_ENCRYPTION_KEY_BASE64 must decode to exactly "
                            + KEY_LENGTH_BYTES + " bytes (256-bit AES), but decoded to "
                            + keyBytes.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertextWithTag.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertextWithTag, 0, combined, nonce.length, ciphertextWithTag.length);

            return VERSION_PREFIX + URL_ENCODER.encodeToString(combined);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encryption failed", ex);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        if (!ciphertext.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException(
                    "Unsupported ciphertext version: missing or unrecognized prefix");
        }
        byte[] combined;
        try {
            combined = URL_DECODER.decode(ciphertext.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Ciphertext body is not valid Base64URL", ex);
        }
        // Minimum valid payload is a 12-byte nonce plus a 16-byte GCM tag (a
        // ciphertext that decrypts to zero plaintext bytes is exactly these 28
        // bytes). Anything shorter cannot be a well-formed AES-GCM payload.
        if (combined.length < NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / Byte.SIZE) {
            throw new IllegalArgumentException(
                    "Ciphertext is too short: must contain a 12-byte nonce and a 16-byte GCM tag");
        }
        byte[] nonce = Arrays.copyOfRange(combined, 0, NONCE_LENGTH_BYTES);
        byte[] ciphertextWithTag = Arrays.copyOfRange(combined, NONCE_LENGTH_BYTES, combined.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(ciphertextWithTag), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            // A failed GCM tag verification lands here: the ciphertext was
            // tampered with or the wrong key was used.
            throw new IllegalStateException("AES-GCM decryption failed (tampered or corrupted ciphertext)", ex);
        }
    }
}
