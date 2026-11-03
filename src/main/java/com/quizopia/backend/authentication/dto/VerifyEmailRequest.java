package com.quizopia.backend.authentication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/verify-email} — verifies the account OTP. The email
 * is used to locate the account; unknown emails answer like wrong OTPs so the
 * endpoint cannot enumerate accounts.
 */
public record VerifyEmailRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 6, max = 6) String otp
) {
}
