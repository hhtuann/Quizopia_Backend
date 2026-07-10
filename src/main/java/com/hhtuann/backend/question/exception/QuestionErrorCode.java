package com.hhtuann.backend.question.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable error codes for the Question Bank API. Same convention as
 * {@link com.hhtuann.backend.authentication.exception.AuthErrorCode}.
 */
public enum QuestionErrorCode {

    QUESTION_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Question bank request validation failed"),
    QUESTION_BANK_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this question bank resource"),
    QUESTION_SUBJECT_SCHOOL_MISMATCH(HttpStatus.FORBIDDEN, "Subject does not belong to the caller's school"),
    QUESTION_SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Subject not found"),
    QUESTION_TEACHER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Teacher profile not found for the current user"),
    QUESTION_BANK_NOT_FOUND(HttpStatus.NOT_FOUND, "Question bank not found"),
    QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Question not found"),
    QUESTION_BANK_CODE_CONFLICT(HttpStatus.CONFLICT, "Question bank code already exists for this owner"),

    // Import errors
    QUESTION_IMPORT_FILE_INVALID(HttpStatus.BAD_REQUEST, "The uploaded file is not a valid xlsx workbook"),
    QUESTION_IMPORT_TEMPLATE_INVALID(HttpStatus.BAD_REQUEST, "The workbook does not match the expected template structure"),
    QUESTION_IMPORT_ROW_INVALID(HttpStatus.BAD_REQUEST, "A row in the import file is invalid"),
    QUESTION_IMPORT_DUPLICATE_CODE(HttpStatus.CONFLICT, "Duplicate question code"),
    QUESTION_IMPORT_UNSUPPORTED_TYPE(HttpStatus.BAD_REQUEST, "Unsupported question type"),
    QUESTION_IMPORT_INVALID_OPTIONS(HttpStatus.BAD_REQUEST, "Invalid question options"),
    QUESTION_IMPORT_INVALID_CORRECT_ANSWERS(HttpStatus.BAD_REQUEST, "Invalid correct answers"),
    QUESTION_IMPORT_INVALID_TRUE_FALSE_MATRIX(HttpStatus.BAD_REQUEST, "Invalid true/false matrix"),
    QUESTION_IMPORT_INVALID_NUMERIC_ANSWER(HttpStatus.BAD_REQUEST, "Invalid numeric answer"),
    QUESTION_IMPORT_ROUNDING_INSTRUCTION_REQUIRED(HttpStatus.BAD_REQUEST, "Rounding instruction is required for numeric questions");

    private final HttpStatusCode status;
    private final String defaultMessage;

    QuestionErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() { return status; }
    public int statusCode() { return status.value(); }
    public String code() { return name(); }
    public String defaultMessage() { return defaultMessage; }
}
