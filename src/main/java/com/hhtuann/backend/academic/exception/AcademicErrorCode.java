package com.hhtuann.backend.academic.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable error codes for the academic API (subjects). Same convention as
 * {@link com.hhtuann.backend.authentication.exception.AuthErrorCode} and the
 * other domain error-code enums: the enum name is the stable contract; the
 * default message is informational and never contains secrets.
 */
public enum AcademicErrorCode {

    ACADEMIC_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this academic resource"),
    ACADEMIC_TEACHER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Teacher profile not found for the current user");

    private final HttpStatusCode status;
    private final String defaultMessage;

    AcademicErrorCode(HttpStatusCode status, String defaultMessage) {
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
