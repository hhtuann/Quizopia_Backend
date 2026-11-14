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
            "Timeline cursor is malformed"),

    // P4-B camera preflight & readiness leases (issues #84–#87, ADR-008 D2/D3).
    // Preflight-lease codes are 409 (state conflicts), CAMERA_REVOKED is 403
    // (authorization consequence) and INFRA_UNAVAILABLE is 503 (upstream down).

    MONITORING_PREFLIGHT_NOT_APPLICABLE(HttpStatus.CONFLICT, "MONITORING_PREFLIGHT_NOT_APPLICABLE",
            "This session does not use camera monitoring"),
    MONITORING_PREFLIGHT_REQUIRED(HttpStatus.CONFLICT, "MONITORING_PREFLIGHT_REQUIRED",
            "Camera readiness verification is required before starting this attempt"),
    MONITORING_PREFLIGHT_EXPIRED(HttpStatus.CONFLICT, "MONITORING_PREFLIGHT_EXPIRED",
            "Camera preflight or readiness lease has expired"),
    MONITORING_PREFLIGHT_INVALID(HttpStatus.CONFLICT, "MONITORING_PREFLIGHT_INVALID",
            "Camera readiness lease is invalid"),
    MONITORING_PREFLIGHT_USED(HttpStatus.CONFLICT, "MONITORING_PREFLIGHT_USED",
            "Camera readiness lease has already been used"),
    MONITORING_PREFLIGHT_NOT_FOUND(HttpStatus.NOT_FOUND, "MONITORING_PREFLIGHT_NOT_FOUND",
            "Camera preflight not found"),
    MONITORING_CAMERA_REVOKED(HttpStatus.FORBIDDEN, "MONITORING_CAMERA_REVOKED",
            "Camera access has been revoked for this attempt"),
    MONITORING_CAMERA_ALREADY_REVOKED(HttpStatus.CONFLICT, "MONITORING_CAMERA_ALREADY_REVOKED",
            "Camera access has already been revoked for this participant"),
    MONITORING_BINDING_MISSING(HttpStatus.CONFLICT, "MONITORING_BINDING_MISSING",
            "No active camera binding for this attempt"),
    MONITORING_INFRA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MONITORING_INFRA_UNAVAILABLE",
            "Camera monitoring infrastructure is unavailable");

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
