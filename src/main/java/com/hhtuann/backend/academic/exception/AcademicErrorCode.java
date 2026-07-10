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
    ACADEMIC_TEACHER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Teacher profile not found for the current user"),
    ACADEMIC_SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Subject not found"),
    ACADEMIC_SUBJECT_CODE_CONFLICT(HttpStatus.CONFLICT, "A subject with this code already exists for the school and grade level"),
    ACADEMIC_GRADE_LEVEL_NOT_FOUND(HttpStatus.NOT_FOUND, "Grade level not found in this school"),
    ACADEMIC_SCHOOL_NOT_FOUND(HttpStatus.NOT_FOUND, "School not found"),
    ACADEMIC_SCHOOL_CODE_CONFLICT(HttpStatus.CONFLICT, "A school with this code already exists"),
    ACADEMIC_STUDENT_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Student already has a profile"),
    ACADEMIC_STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "User is not a registered student"),
    ACADEMIC_SCHOOL_COUNTER_FAILED(HttpStatus.CONFLICT, "Failed to generate student code");

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
