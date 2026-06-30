package com.hhtuann.backend.authentication.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable error codes for the authentication API. Each code carries the HTTP
 * status it maps to and a safe, non-sensitive default message. The code string
 * (the enum name) is the stable contract returned to clients; the message is
 * informational only and must never contain secrets, identifiers, hashes or
 * ciphertext.
 */
public enum AuthErrorCode {

    AUTH_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    AUTH_USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "Username already exists"),
    AUTH_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already exists"),
    AUTH_TEACHER_INVITE_INVALID(HttpStatus.FORBIDDEN, "Teacher invite code is invalid"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    AUTH_ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account is temporarily locked"),
    AUTH_ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account is disabled"),
    AUTH_ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "Account is pending activation"),
    AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is invalid"),
    AUTH_REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token has expired"),
    AUTH_REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked"),
    AUTH_REFRESH_TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
    AUTH_ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is invalid or missing"),
    AUTH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
    AUTH_ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "Origin is not allowed"),

    /** System-error fallback (e.g. missing role seed). Never leaks detail. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatusCode status;
    private final String defaultMessage;

    AuthErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() {
        return status;
    }

    public int statusCode() {
        return status.value();
    }

    public String code() {
        return name();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
