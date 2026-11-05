package com.quizopia.backend.authentication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/oauth/google/link} — starts the authenticated linking
 * flow. The password is the account re-authentication proof (review M1-06);
 * it is verified against the stored Argon2id hash and never persisted or
 * logged beyond this request.
 */
public record LinkGoogleRequest(
        @NotBlank @Size(min = 8, max = 128) String password
) {
}
