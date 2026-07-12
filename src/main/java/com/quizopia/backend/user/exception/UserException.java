package com.quizopia.backend.user.exception;

/**
 * Unchecked exception carrying a {@link UserErrorCode}. Same convention as
 * {@code AcademicException}; rendered as the unified {@code ApiError} body by
 * {@code GlobalExceptionHandler}.
 */
public class UserException extends RuntimeException {

    private final UserErrorCode errorCode;

    public UserException(UserErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public UserException(UserErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public UserErrorCode getErrorCode() {
        return errorCode;
    }
}
