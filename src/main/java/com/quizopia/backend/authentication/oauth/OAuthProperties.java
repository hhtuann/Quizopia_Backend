package com.quizopia.backend.authentication.oauth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth/OIDC login configuration (ADR-002 §4). Bound from
 * {@code quizopia.oauth.*}. The Google client exists only when a client id is
 * configured; with no credentials the login endpoints report the provider as
 * disabled and no client registration bean is created.
 *
 * <p>{@code issuer}/{@code jwksUri} default to the real Google endpoints and
 * can be pointed at a simulated provider in tests.
 */
@Validated
@ConfigurationProperties(prefix = "quizopia.oauth")
public class OAuthProperties {

    /**
     * Absolute browser URL of the SPA callback page. The success handler
     * redirects here with {@code ?code=<opaque one-time code>} (never a JWT);
     * failures redirect with {@code ?error=<code>}.
     */
    @NotBlank
    private String frontendRedirectUrl = "http://localhost:3000/auth/callback";

    private final Google google = new Google();

    public static class Google {

        /** Client id; blank disables Google login entirely. */
        private String clientId = "";

        /** Client secret — backend only, never shipped to the browser. */
        private String clientSecret = "";

        /** Expected ID-token issuer. */
        private String issuer = "https://accounts.google.com";

        /** JWKS endpoint used to verify ID-token signatures. */
        private String jwksUri = "https://www.googleapis.com/oauth2/v3/certs";

        /** Absolute base URI of this backend (redirect_uri = base + path). */
        private String redirectBaseUri = "http://localhost:8080";

        /**
         * Provider authorization endpoint. Defaults to Google; overridable so
         * integration tests can point it at a simulated OIDC provider
         * (review R2-M1-D).
         */
        private String authorizationUri = OAuth2ClientConfig.DEFAULT_AUTHORIZATION_URI;

        /** Provider token endpoint (authorization-code + PKCE exchange). */
        private String tokenUri = OAuth2ClientConfig.DEFAULT_TOKEN_URI;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getRedirectBaseUri() {
            return redirectBaseUri;
        }

        public void setRedirectBaseUri(String redirectBaseUri) {
            this.redirectBaseUri = redirectBaseUri;
        }

        /**
         * Fail closed on PARTIAL configuration (review M1-07): a client id
         * without a secret cannot complete a confidential authorization-code
         * exchange, so the provider is only "configured" when BOTH are set.
         * The providers endpoint, the login chain and linking all gate on
         * this — a half-configured deployment exposes no broken flow.
         */
        public String getAuthorizationUri() {
            return authorizationUri;
        }

        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public String getFrontendRedirectUrl() {
        return frontendRedirectUrl;
    }

    public void setFrontendRedirectUrl(String frontendRedirectUrl) {
        this.frontendRedirectUrl = frontendRedirectUrl;
    }

    public Google getGoogle() {
        return google;
    }
}
