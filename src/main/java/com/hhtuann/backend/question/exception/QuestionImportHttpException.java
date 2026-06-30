package com.hhtuann.backend.question.exception;

import org.springframework.http.HttpStatus;

/**
 * Unchecked exception carrying both a {@link QuestionErrorCode} and an explicit
 * {@link HttpStatus}. Used by the import controller to surface the SAME stable
 * error code (e.g. {@link QuestionErrorCode#QUESTION_IMPORT_FILE_INVALID},
 * whose default status is 400) at different HTTP statuses — 413 for a file that
 * is too large, 415 for a wrong media type — while keeping the body shape
 * produced by {@link com.hhtuann.backend.question.api.QuestionImportHttpExceptionHandler}.
 *
 * <p>Unlike {@link QuestionException}, the HTTP status is not derived from the
 * error code; it is chosen at the throw site.
 */
public class QuestionImportHttpException extends RuntimeException {

    private final QuestionErrorCode errorCode;
    private final HttpStatus httpStatus;

    public QuestionImportHttpException(QuestionErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public QuestionErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
