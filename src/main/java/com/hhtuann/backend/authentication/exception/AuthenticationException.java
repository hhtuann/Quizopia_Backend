package com.hhtuann.backend.authentication.exception;

/**
 * Unchecked exception carrying an {@link AuthErrorCode}. Thrown by the
 * authentication application services to signal a controlled, client-facing
 * failure (validation, duplicate identifier, invalid invite code, invalid
 * credentials, locked / disabled / pending account, refresh-token lifecycle
 * errors). The {@link AuthErrorCode} determines the HTTP status and stable
 * error code; the message overrides the default and must never contain secrets.
 */
public class AuthenticationException extends RuntimeException {

    private final AuthErrorCode errorCode;

    public AuthenticationException(AuthErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AuthenticationException(AuthErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AuthErrorCode getErrorCode() {
        return errorCode;
    }
}
