package com.quizopia.backend.question.exception;

import org.springframework.http.HttpStatus;

/**
 * Unchecked exception carrying an {@link AiAuthoringErrorCode} and an explicit
 * HTTP status, mirroring {@link QuestionImportHttpException}. The message is
 * always server-authored: upstream provider bodies, API keys and extracted
 * document text are never included.
 */
public class AiAuthoringException extends RuntimeException {

    private final AiAuthoringErrorCode errorCode;
    private final HttpStatus httpStatus;

    public AiAuthoringException(AiAuthoringErrorCode errorCode, String message) {
        this(errorCode, errorCode.status(), message);
    }

    public AiAuthoringException(AiAuthoringErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public AiAuthoringErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
