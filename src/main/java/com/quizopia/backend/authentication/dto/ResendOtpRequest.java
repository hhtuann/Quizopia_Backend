package com.quizopia.backend.authentication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/verify-email/resend} — re-issues the account OTP.
 * Unknown emails answer like successes (anti-enumeration).
 */
public record ResendOtpRequest(
        @NotBlank @Email @Size(max = 254) String email
) {
}
