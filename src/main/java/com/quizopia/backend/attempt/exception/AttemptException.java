package com.quizopia.backend.attempt.exception;

/**
 * Domain exception for the attempt module, carrying an {@link AttemptErrorCode}.
 */
public class AttemptException extends RuntimeException {

    private final AttemptErrorCode errorCode;

    public AttemptException(AttemptErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AttemptException(AttemptErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AttemptErrorCode getErrorCode() {
        return errorCode;
    }
}
