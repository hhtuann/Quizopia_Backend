package com.quizopia.backend.authentication.exception;

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
    AUTH_EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address is not verified"),
    AUTH_TEACHER_REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "Teacher registration is not available on the public form"),
    AUTH_OTP_INVALID(HttpStatus.BAD_REQUEST, "Verification code is invalid"),
    AUTH_OTP_EXPIRED(HttpStatus.BAD_REQUEST, "Verification code has expired"),
    AUTH_OTP_MAX_ATTEMPTS(HttpStatus.BAD_REQUEST, "Too many invalid attempts, request a new code"),
    AUTH_OTP_NOT_FOUND(HttpStatus.BAD_REQUEST, "No active verification code, request a new one"),
    AUTH_OTP_RESEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "Please wait before requesting a new code"),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    AUTH_OAUTH_DISABLED(HttpStatus.NOT_FOUND, "OAuth login is not configured"),
    AUTH_OAUTH_EMAIL_CONFLICT(HttpStatus.CONFLICT, "An account with this email already exists"),
    AUTH_OAUTH_EMAIL_NOT_VERIFIED(HttpStatus.UNAUTHORIZED, "Google reports this email as not verified"),
    AUTH_OAUTH_CODE_INVALID(HttpStatus.UNAUTHORIZED, "Login code is invalid or expired"),
    AUTH_OAUTH_STATE_INVALID(HttpStatus.UNAUTHORIZED, "OAuth state validation failed"),
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
