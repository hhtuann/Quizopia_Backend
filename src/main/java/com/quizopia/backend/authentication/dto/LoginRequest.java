package com.quizopia.backend.authentication.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request. {@code identifier} is a username (no {@code @}) or an email
 * (contains {@code @}); the service picks the lookup strategy accordingly.
 */
public record LoginRequest(
        @NotBlank String identifier,
        @NotBlank String password
) {
}
