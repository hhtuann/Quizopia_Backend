package com.hhtuann.backend.security.token;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * {@link RefreshTokenHasher} backed by SHA-256.
 *
 * <p>The digest is rendered as exactly 64 lowercase hexadecimal characters,
 * matching the {@code refresh_sessions.token_hash} CHECK constraint. The hash
 * is deterministic for a given token and suitable as a unique lookup key.
 */
@Component
public class Sha256RefreshTokenHasher implements RefreshTokenHasher {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final int EXPECTED_HEX_LENGTH = 64;

    @Override
    public String hash(String token) {
        Objects.requireNonNull(token, "token must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return toLowerHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is part of every JDK; reaching here is a JVM misconfiguration.
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            hex[i * 2] = HEX_DIGITS[value >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        String result = new String(hex);
        // SHA-256 always yields 32 bytes (64 hex chars). This is a real runtime
        // check, not a disabled-by-default assert: if the JVM's MessageDigest is
        // somehow misconfigured we must fail loudly rather than silently return a
        // hash that violates the refresh_sessions.token_hash CHECK constraint.
        if (result.length() != EXPECTED_HEX_LENGTH) {
            throw new IllegalStateException(
                    "SHA-256 produced an unexpected digest length: " + result.length());
        }
        return result;
    }
}
