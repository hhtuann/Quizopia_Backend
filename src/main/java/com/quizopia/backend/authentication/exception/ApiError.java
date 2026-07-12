package com.quizopia.backend.authentication.exception;

import java.time.Instant;

/**
 * Unified error response body for the authentication API and the security web
 * layer. Carries a stable {@code code}, the HTTP {@code status}, a safe
 * {@code message}, the request {@code path} and a {@code traceId} when tracing
 * is active. It never contains a stack trace, an exception class name, or any
 * secret data.
 *
 * @param timestamp when the error was produced
 * @param status    the HTTP status code
 * @param code      the stable error code (an {@link AuthErrorCode} name)
 * @param message   a safe, non-sensitive human-readable message
 * @param path      the request path
 * @param traceId   the trace id when distributed tracing is active, otherwise {@code null}
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId
) {
}
