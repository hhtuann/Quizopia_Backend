package com.quizopia.backend.question.exception;

/**
 * Unchecked exception carrying a {@link QuestionErrorCode}. Same convention as
 * {@link com.quizopia.backend.authentication.exception.AuthenticationException}.
 */
public class QuestionException extends RuntimeException {

    private final QuestionErrorCode errorCode;

    public QuestionException(QuestionErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public QuestionException(QuestionErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public QuestionErrorCode getErrorCode() {
        return errorCode;
    }
}
