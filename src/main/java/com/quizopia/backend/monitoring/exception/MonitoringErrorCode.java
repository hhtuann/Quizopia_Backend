package com.quizopia.backend.monitoring.exception;

import org.springframework.http.HttpStatus;

/**
 * Error codes for the P4-A monitoring module (issues #81/#83). Each carries an
 * HTTP status, a stable code string and a generic default message — no SQL,
 * constraint names or payload echoes.
 */
public enum MonitoringErrorCode {
    MONITORING_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "MONITORING_VALIDATION_ERROR",
            "Activity event request validation failed"),
    MONITORING_EVENT_TYPE_REJECTED(HttpStatus.BAD_REQUEST, "MONITORING_EVENT_TYPE_REJECTED",
            "Event type is not a client-observable P4-A activity type"),
    MONITORING_METADATA_TOO_LARGE(HttpStatus.BAD_REQUEST, "MONITORING_METADATA_TOO_LARGE",
            "Event metadata exceeds the size bound"),
    MONITORING_BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "MONITORING_BATCH_TOO_LARGE",
            "Activity event batch exceeds the size bound"),
    MONITORING_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "MONITORING_RATE_LIMITED",
            "Too many activity-event batches; retry later"),
    MONITORING_ACCESS_DENIED(HttpStatus.FORBIDDEN, "MONITORING_ACCESS_DENIED",
            "Access denied to this attempt activity resource"),
    MONITORING_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "MONITORING_ATTEMPT_NOT_FOUND",
            "Attempt not found"),
    MONITORING_CURSOR_INVALID(HttpStatus.BAD_REQUEST, "MONITORING_CURSOR_INVALID",
            "Timeline cursor is malformed");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    MonitoringErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
