package com.quizopia.backend.monitoring.exception;

/**
 * Monitoring-module exception. Carries an optional {@code retryAfterSeconds}
 * (ingest rate limiting) that the global handler renders as a
 * {@code Retry-After} response header — mirrors the community exception
 * pattern.
 */
public class MonitoringException extends RuntimeException {

    private final MonitoringErrorCode errorCode;
    private final long retryAfterSeconds;

    public MonitoringException(MonitoringErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), 0);
    }

    public MonitoringException(MonitoringErrorCode errorCode, String message) {
        this(errorCode, message, 0);
    }

    public MonitoringException(MonitoringErrorCode errorCode, long retryAfterSeconds) {
        this(errorCode, errorCode.defaultMessage(), retryAfterSeconds);
    }

    private MonitoringException(MonitoringErrorCode errorCode, String message, long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public MonitoringErrorCode getErrorCode() {
        return errorCode;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
