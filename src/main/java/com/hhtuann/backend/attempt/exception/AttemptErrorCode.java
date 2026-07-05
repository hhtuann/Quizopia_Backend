package com.hhtuann.backend.attempt.exception;

import org.springframework.http.HttpStatus;

/**
 * Error codes for the attempt module. Each carries an HTTP status, a stable
 * code string (for client mapping), and a generic default message. No SQL,
 * constraint, or stack-trace information is exposed.
 */
public enum AttemptErrorCode {

    ATTEMPT_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "ATTEMPT_VALIDATION_ERROR", "Attempt request validation failed"),
    ATTEMPT_INVALID_ANSWER_PAYLOAD(HttpStatus.BAD_REQUEST, "ATTEMPT_INVALID_ANSWER_PAYLOAD", "Answer payload does not match the question type"),
    ATTEMPT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "ATTEMPT_ACCESS_DENIED", "Access denied to this attempt resource"),
    ATTEMPT_STUDENT_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTEMPT_STUDENT_PROFILE_NOT_FOUND", "Student profile not found"),
    EXAM_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "EXAM_SESSION_NOT_FOUND", "Exam session not found"),
    ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt not found"),
    ATTEMPT_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTEMPT_QUESTION_NOT_FOUND", "Attempt question not found"),
    ATTEMPT_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "ATTEMPT_PARTICIPANT_NOT_FOUND", "Exam session participant not found"),
    ATTEMPT_PARTICIPANT_BLOCKED(HttpStatus.CONFLICT, "ATTEMPT_PARTICIPANT_BLOCKED", "Participant is blocked from this session"),
    ATTEMPT_SESSION_NOT_OPEN(HttpStatus.CONFLICT, "ATTEMPT_SESSION_NOT_OPEN", "Exam session is not open"),
    ATTEMPT_OUTSIDE_WINDOW(HttpStatus.CONFLICT, "ATTEMPT_OUTSIDE_WINDOW", "Current time is outside the session window"),
    ATTEMPT_MAX_REACHED(HttpStatus.CONFLICT, "ATTEMPT_MAX_REACHED", "Maximum attempts reached for this session"),
    ATTEMPT_INVALID_STATE(HttpStatus.CONFLICT, "ATTEMPT_INVALID_STATE", "Attempt state transition is invalid"),
    ATTEMPT_DEADLINE_EXCEEDED(HttpStatus.CONFLICT, "ATTEMPT_DEADLINE_EXCEEDED", "Attempt deadline has been exceeded"),
    ATTEMPT_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "ATTEMPT_ALREADY_SUBMITTED", "Attempt has already been submitted"),
    ATTEMPT_DUPLICATE_START(HttpStatus.CONFLICT, "ATTEMPT_DUPLICATE_START", "A concurrent start race was detected"),
    ATTEMPT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "ATTEMPT_IDEMPOTENCY_CONFLICT", "Idempotency conflict"),
    // Day 8 result/grading error codes
    ATTEMPT_NOT_SUBMITTED(HttpStatus.CONFLICT, "ATTEMPT_NOT_SUBMITTED", "Attempt has not been submitted"),
    ATTEMPT_NOT_GRADED(HttpStatus.CONFLICT, "ATTEMPT_NOT_GRADED", "Attempt has not been graded"),
    RESULT_NOT_FOUND(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "Result not found"),
    RESULT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "RESULT_ACCESS_DENIED", "Access denied to this result"),
    SESSION_RESULTS_ACCESS_DENIED(HttpStatus.FORBIDDEN, "SESSION_RESULTS_ACCESS_DENIED", "Access denied to session results"),
    INVALID_RESULT_QUERY(HttpStatus.BAD_REQUEST, "INVALID_RESULT_QUERY", "Invalid result query parameters"),
    EXPORT_FAILED_INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED_INTERNAL", "Export failed");

    private final HttpStatus statusCode;
    private final String code;
    private final String defaultMessage;

    AttemptErrorCode(HttpStatus statusCode, String code, String defaultMessage) {
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
