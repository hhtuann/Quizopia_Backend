package com.quizopia.backend.grading;

import org.springframework.http.HttpStatus;

/**
 * Day 8 grading error codes. Each carries an HTTP status, a stable code string, and a generic default
 * message — mirroring {@code AttemptErrorCode}. The default message is safe (no answer key, no SQL, no
 * stack); the internal {@link GradingException} message is never returned to a client.
 */
public enum GradingErrorCode {

    GRADING_CONFIGURATION_INVALID(HttpStatus.CONFLICT, "GRADING_CONFIGURATION_INVALID", "Grading configuration is invalid"),
    GRADING_DATA_INCONSISTENT(HttpStatus.CONFLICT, "GRADING_DATA_INCONSISTENT", "Grading data is inconsistent");

    private final HttpStatus statusCode;
    private final String code;
    private final String defaultMessage;

    GradingErrorCode(HttpStatus statusCode, String code, String defaultMessage) {
        this.statusCode = statusCode;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int statusCode() {
        return statusCode.value();
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
