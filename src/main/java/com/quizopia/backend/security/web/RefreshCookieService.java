package com.quizopia.backend.security.web;

import com.quizopia.backend.security.config.SecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the {@code quizopia_refresh} HttpOnly cookie from {@link SecurityProperties.Cookie}
 * so that cookie-attribute logic stays out of the controller. The raw refresh
 * token is never logged and never appears in a JSON body.
 */
@Component
public class RefreshCookieService {

    private final SecurityProperties properties;

    public RefreshCookieService(SecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * Sets the refresh cookie with the given max age (7 days at login, the same
     * family lifetime on rotation).
     */
    public void setRefreshCookie(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader("Set-Cookie", cookie(token, maxAge).toString());
    }

    /**
     * Clears the refresh cookie by writing the same name/path/secure/sameSite
     * with a zero max age.
     */
    public void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String token, Duration maxAge) {
        SecurityProperties.Cookie cookie = properties.getCookie();
        return ResponseCookie.from(cookie.getName(), token)
                .httpOnly(cookie.isHttpOnly())
                .sameSite(cookie.getSameSite())
                .path(cookie.getPath())
                .secure(cookie.isSecure())
                .maxAge(maxAge)
                .build();
    }
}
