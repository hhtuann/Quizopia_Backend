package com.quizopia.backend.security.otp;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * OTP generation and verification primitives (ADR-002 §2).
 *
 * <p>Digits come from {@link SecureRandom}. Verification digests are
 * HMAC-SHA256 under a key derived from the existing 32-byte data-encryption
 * key via domain separation ({@code HMAC(K_enc, "quizopia.otp.hmac.v1")}), so
 * no additional secret must be operated. Plaintext OTPs are never stored or
 * logged. Comparison is constant-time.
 */
@Component
public class OtpHasher {

    private static final String HMAC_KEY_DERIVATION_LABEL = "quizopia.otp.hmac.v1";
    static final int OTP_LENGTH = 6;

    private final byte[] hmacKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpHasher(com.quizopia.backend.security.config.SecurityProperties properties) {
        byte[] encryptionKey = java.util.Base64.getDecoder()
                .decode(properties.getEncryption().getKeyBase64());
        this.hmacKey = hmac(encryptionKey,
                HMAC_KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    /** Generates a fresh numeric OTP from {@link SecureRandom}. */
    public String generateOtp() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    /** Hex HMAC-SHA256 of the OTP under the derived key. */
    public String hash(String otp) {
        byte[] mac = hmac(hmacKey, otp.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder(2 * mac.length);
        for (byte b : mac) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Constant-time OTP-vs-hash comparison. */
    public boolean matches(String otp, String expectedHash) {
        if (otp == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(otp).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
