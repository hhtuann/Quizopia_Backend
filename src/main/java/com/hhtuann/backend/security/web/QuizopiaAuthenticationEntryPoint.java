package com.hhtuann.backend.security.web;

import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders the unified 401 body for every security-layer authentication failure:
 * missing or malformed bearer token, bad signature / issuer / audience, expired
 * token, unknown subject, non-ACTIVE account, or token-version mismatch. All of
 * these map to {@link AuthErrorCode#AUTH_ACCESS_TOKEN_INVALID} (never 500).
 */
@Component
public class QuizopiaAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter apiErrorWriter;

    public QuizopiaAuthenticationEntryPoint(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        apiErrorWriter.write(response, AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID, request);
    }
}
