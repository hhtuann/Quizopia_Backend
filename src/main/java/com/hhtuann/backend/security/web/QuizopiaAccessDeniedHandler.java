package com.hhtuann.backend.security.web;

import com.hhtuann.backend.authentication.exception.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders the unified 403 body when an authenticated principal lacks the
 * authority required for the requested endpoint (maps to
 * {@link AuthErrorCode#AUTH_ACCESS_DENIED}).
 */
@Component
public class QuizopiaAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter apiErrorWriter;

    public QuizopiaAccessDeniedHandler(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        apiErrorWriter.write(response, AuthErrorCode.AUTH_ACCESS_DENIED, request);
    }
}
