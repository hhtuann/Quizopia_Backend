package com.quizopia.backend.authentication.dto;

/**
 * {@code GET /api/auth/providers} — which SSO providers the deployment
 * supports, so the SPA can render the right buttons. No configuration detail
 * beyond enabled flags is exposed.
 */
public record AuthProvidersResponse(boolean googleEnabled) {
}
