package com.hhtuann.backend.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable error codes for the user-management API. Same convention as the other
 * domain error-code enums: the enum name is the stable contract; the default
 * message is informational and never contains secrets.
 */
public enum UserErrorCode {

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    USER_USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "This username is already in use"),
    USER_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "This email is already in use"),
    USER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to user management"),
    USER_INVALID_ROLE(HttpStatus.BAD_REQUEST, "Unknown role code"),
    USER_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed");

    private final HttpStatusCode status;
    private final String defaultMessage;

    UserErrorCode(HttpStatusCode status, String defaultMessage) {
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
