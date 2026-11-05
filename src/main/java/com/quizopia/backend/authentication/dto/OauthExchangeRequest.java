package com.quizopia.backend.authentication.dto;

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
}
