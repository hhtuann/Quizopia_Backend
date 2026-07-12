package com.quizopia.backend.academic.exception;

/**
 * Unchecked exception carrying an {@link AcademicErrorCode}. Same convention as
 * {@link com.quizopia.backend.question.exception.QuestionException}; rendered as
 * the unified {@code ApiError} body by {@code GlobalExceptionHandler}.
 */
public class AcademicException extends RuntimeException {

    private final AcademicErrorCode errorCode;

    public AcademicException(AcademicErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AcademicException(AcademicErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AcademicErrorCode getErrorCode() {
        return errorCode;
    }
}
