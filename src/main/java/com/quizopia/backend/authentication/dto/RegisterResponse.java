package com.quizopia.backend.authentication.dto;

import com.quizopia.backend.identity.domain.model.UserStatus;

import java.util.List;

/**
 * Registration response. {@code phone} echoes the plaintext value the caller
 * just submitted; the stored ciphertext, password hash, token version and any
 * token are deliberately never returned.
 *
 * <p>Identity-M1: {@code emailVerificationRequired} is always {@code true} for
 * new registrations, and {@code emailSent} reports whether the OTP mail was
 * accepted by the transport so the UI can offer an immediate resend when it
 * was not.
 */
public record RegisterResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String phone,
        UserStatus status,
        List<String> roles,
        boolean emailVerificationRequired,
        boolean emailSent
) {
}
