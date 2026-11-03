package com.quizopia.backend.authentication.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * OIDC login failure: redirect to the SPA callback with a generic error code.
 * Details (state mismatch, token rejection) are logged server-side only —
 * they never reach the browser.
 */
public class OauthLoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OauthLoginFailureHandler.class);

    private final OAuthProperties properties;

    public OauthLoginFailureHandler(OAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        log.warn("Google login rejected: {}", exception.toString());
        response.sendRedirect(properties.getFrontendRedirectUrl() + "?error=AUTH_OAUTH_FAILED");
    }
}
