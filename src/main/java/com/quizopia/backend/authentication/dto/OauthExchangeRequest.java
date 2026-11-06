package com.quizopia.backend.authentication.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/oauth/exchange} — swaps the one-time login code from
 * the OIDC callback redirect for the Quizopia token response. The code is an
 * opaque 43-char Base64url value, never a JWT.
 */
public record OauthExchangeRequest(
        @NotBlank @Size(max = 128) String code
) {
    /** ADR-004: mutation DTOs reject unknown fields instead of silently ignoring them. */
    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported field");
    }
}
