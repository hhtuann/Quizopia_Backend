package com.quizopia.backend.security.web;

import tools.jackson.databind.ObjectMapper;
import com.quizopia.backend.authentication.exception.ApiError;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Builds and serializes the unified {@link ApiError} body. Shared by the
 * {@link GlobalExceptionHandler}, the authentication entry point and the access
 * denied handler so that every error path returns the identical JSON shape.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds an {@link ApiError} for the given code, using the request URI as the
     * path and the current MDC {@code traceId} (which is {@code null} when tracing
     * is inactive).
     */
    public ApiError build(AuthErrorCode code, HttpServletRequest request) {
        String path = request != null ? request.getRequestURI() : null;
        return new ApiError(
                Instant.now(),
                code.statusCode(),
                code.code(),
                code.defaultMessage(),
                path,
                MDC.get("traceId"));
    }

    /**
     * Writes the {@link ApiError} body directly to the response. Used by filters
     * and security handlers that bypass the DispatcherServlet.
     */
    public void write(HttpServletResponse response, AuthErrorCode code, HttpServletRequest request) throws IOException {
        ApiError body = build(code, request);
        response.setStatus(code.statusCode());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
