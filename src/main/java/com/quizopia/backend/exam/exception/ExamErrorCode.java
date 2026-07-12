package com.quizopia.backend.exam.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ExamErrorCode {
    EXAM_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Exam request validation failed"),
    EXAM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this exam resource"),
    EXAM_TEACHER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Teacher profile not found for the current user"),
    EXAM_SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Subject not found"),
    EXAM_SUBJECT_SCHOOL_MISMATCH(HttpStatus.FORBIDDEN, "Subject does not belong to the caller's school"),
    EXAM_PURPOSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Exam purpose not found"),
    EXAM_PURPOSE_SCHOOL_MISMATCH(HttpStatus.FORBIDDEN, "Exam purpose does not belong to the caller's school"),
    EXAM_CODE_CONFLICT(HttpStatus.CONFLICT, "Exam code already exists for this owner"),
    EXAM_NOT_FOUND(HttpStatus.NOT_FOUND, "Exam not found"),
    EXAM_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Exam version not found"),
    EXAM_VERSION_NOT_DRAFT(HttpStatus.CONFLICT, "Exam version is not in DRAFT state"),
    EXAM_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "The exam was modified by another transaction"),
    EXAM_PUBLISH_CONFLICT(HttpStatus.CONFLICT, "The exam version was already published"),
    EXAM_SESSION_TIME_INVALID(HttpStatus.BAD_REQUEST, "Exam session time window is invalid"),
    EXAM_SESSION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this exam session resource"),
    EXAM_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Exam session not found"),
    EXAM_SESSION_INVALID_STATE(HttpStatus.CONFLICT, "Exam session state transition is invalid"),
    EXAM_PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "Exam session participant not found"),
    EXAM_PARTICIPANT_DUPLICATE(HttpStatus.CONFLICT, "Duplicate exam session participant");

    private final HttpStatusCode status;
    private final String defaultMessage;

    ExamErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() { return status; }
    public int statusCode() { return status.value(); }
    public String code() { return name(); }
    public String defaultMessage() { return defaultMessage; }
}
