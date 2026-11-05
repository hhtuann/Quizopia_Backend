package com.quizopia.backend.authentication.oauth;

/**
 * A validated external OIDC identity — the output of
 * {@link GoogleIdTokenValidator}. Key fields are issuer and subject; the email
 * is informational and carries the provider's own verification flag.
 */
public record ValidatedOidcIdentity(
        String issuer,
        String subject,
        String email,
        boolean emailVerified) {
}
