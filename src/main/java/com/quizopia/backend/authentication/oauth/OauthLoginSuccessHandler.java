package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.identity.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OIDC login success: re-validates the ID token independently, resolves the
 * Quizopia user (provisioning a STUDENT account on first login), mints a
 * one-time opaque login code and redirects the browser to the SPA callback.
 *
 * <p>No JWT or refresh token ever appears in a URL (ADR-002 §4); the SPA
 * exchanges the code at {@code POST /api/auth/oauth/exchange}.
 */
public class OauthLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OauthLoginSuccessHandler.class);

    private final GoogleIdTokenValidator tokenValidator;
    private final OauthIdentityService identityService;
    private final OAuthProperties properties;

    public OauthLoginSuccessHandler(GoogleIdTokenValidator tokenValidator,
            OauthIdentityService identityService, OAuthProperties properties) {
        this.tokenValidator = tokenValidator;
        this.identityService = identityService;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
                throw new IllegalStateException("Expected an OidcUser principal");
            }
            // Second, independent validation (signature/iss/aud/exp/email_verified).
            ValidatedOidcIdentity identity = tokenValidator.validate(
                    oidcUser.getIdToken().getTokenValue(), null);
            User user = identityService.resolveGoogleUser(identity);
            String code = identityService.mintExchangeCode(user);
            log.info("Google login succeeded for user {}", user.getId());
            response.sendRedirect(appendParam(properties.getFrontendRedirectUrl(), "code", code));
        } catch (Exception ex) {
            // Fail closed: never redirect with tokens on a partial failure.
            log.warn("Google login failed at success handling: {}", ex.toString());
            response.sendRedirect(appendParam(properties.getFrontendRedirectUrl(),
                    "error", "AUTH_OAUTH_FAILED"));
        }
    }

    private static String appendParam(String base, String name, String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + name + "=" + encoded;
    }
}
