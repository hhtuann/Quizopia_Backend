package com.quizopia.backend.authentication.dto;

/**
 * Snapshot of client request metadata used when creating refresh sessions.
 * Both fields are optional. The raw remote address is preferred over any
 * {@code X-Forwarded-For} header because no trusted reverse proxy is configured
 * in this MVP.
 */
public record ClientContext(
        String userAgent,
        String remoteAddress
) {
}
