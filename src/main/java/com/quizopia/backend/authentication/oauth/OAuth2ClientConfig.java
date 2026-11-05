package com.quizopia.backend.authentication.oauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Google OIDC client wiring (ADR-002 §4).
 *
 * <p>The client registration exists ONLY when a client id is configured
 * ({@code quizopia.oauth.google.client-id}); with no credentials no bean is
 * created, {@code /api/auth/providers} reports the provider as disabled and
 * the OIDC login filter chain is absent.
 *
 * <p>Every authorization request is built by the framework resolver with a
 * state parameter (validated on callback), a nonce for openid-scope requests
 * (validated against the ID token), and forced PKCE (S256) via the supported
 * {@link OAuth2AuthorizationRequestCustomizers#withPkce()} — defense in depth
 * even for this confidential client.
 */
@Configuration
public class OAuth2ClientConfig {

    /** Google's fixed endpoints (issuer/jwks overridable for simulated providers in tests). */
    static final String DEFAULT_AUTHORIZATION_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String DEFAULT_USER_INFO_URI = "https://openidconnect.googleapis.com/v1/userinfo";
    static final String DEFAULT_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** OIDC redirect endpoint (also the login-processing URL of the OAuth chain). */
    public static final String CALLBACK_PATH = "/api/auth/oauth/google/callback";

    private final OAuthProperties properties;

    public OAuth2ClientConfig(OAuthProperties properties) {
        this.properties = properties;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${quizopia.oauth.google.client-id:}' != '' and '${quizopia.oauth.google.client-secret:}' != ''")
    public ClientRegistrationRepository googleClientRegistrationRepository() {
        OAuthProperties.Google google = properties.getGoogle();
        ClientRegistration googleRegistration = ClientRegistration.withRegistrationId("google")
                .clientId(google.getClientId())
                .clientSecret(google.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(google.getRedirectBaseUri() + CALLBACK_PATH)
                .scope(OidcScopes.OPENID, OidcScopes.PROFILE, OidcScopes.EMAIL)
                .authorizationUri(google.getAuthorizationUri() != null && !google.getAuthorizationUri().isBlank()
                        ? google.getAuthorizationUri() : DEFAULT_AUTHORIZATION_URI)
                .tokenUri(google.getTokenUri() != null && !google.getTokenUri().isBlank()
                        ? google.getTokenUri() : DEFAULT_TOKEN_URI)
                .issuerUri(google.getIssuer())
                .jwkSetUri(google.getJwksUri() != null && !google.getJwksUri().isBlank()
                        ? google.getJwksUri() : DEFAULT_JWKS_URI)
                .userInfoUri(DEFAULT_USER_INFO_URI)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Google")
                .build();
        return new InMemoryClientRegistrationRepository(googleRegistration);
    }

    /**
     * Authorization requests with forced PKCE (S256); state and nonce are
     * framework-managed. Only instantiated when a client registration exists.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ClientRegistrationRepository.class)
    public OAuth2AuthorizationRequestResolver googleAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /** Production JWKS source for ID-token signature verification. */
    @Bean
    public JWKSource<SecurityContext> googleJwkSource() {
        String jwksUri = properties.getGoogle().getJwksUri();
        if (jwksUri == null || jwksUri.isBlank()) {
            jwksUri = DEFAULT_JWKS_URI;
        }
        try {
            return new RemoteJWKSet<>(new URL(jwksUri));
        } catch (MalformedURLException ex) {
            throw new IllegalStateException("Invalid quizopia.oauth.google.jwks-uri", ex);
        }
    }

    /**
     * Fail-fast configuration guard (review M1-07): the browser-facing OAuth
     * redirects (login + linking) may only target an origin the deployment
     * already trusts for CORS. A misconfigured redirect URL must abort
     * startup instead of silently bouncing users (or one-time codes) to an
     * untrusted origin.
     */
    @Bean
    public String oauthRedirectOriginGuard(
            com.quizopia.backend.security.config.SecurityProperties securityProperties) {
        String redirectUrl = properties.getFrontendRedirectUrl();
        try {
            String origin = new URL(redirectUrl).toURI().resolve("/").toString();
            String originNoSlash = origin.endsWith("/")
                    ? origin.substring(0, origin.length() - 1) : origin;
            boolean trusted = securityProperties.getCors().getAllowedOrigins().stream()
                    .anyMatch(allowed -> allowed.equalsIgnoreCase(originNoSlash)
                            || allowed.equalsIgnoreCase(origin));
            if (!trusted) {
                throw new IllegalStateException(
                        "quizopia.oauth.frontend-redirect-url origin '" + originNoSlash
                                + "' is not in quizopia.security.cors.allowed-origins; refusing to start");
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "quizopia.oauth.frontend-redirect-url is not a valid absolute URL: "
                            + redirectUrl, ex);
        }
        return redirectUrl;
    }
}
