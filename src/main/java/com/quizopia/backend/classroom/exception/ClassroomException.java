package com.quizopia.backend.classroom.exception;

public class ClassroomException extends RuntimeException {
    private final ClassroomErrorCode errorCode;

    public ClassroomException(ClassroomErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ClassroomException(ClassroomErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ClassroomErrorCode getErrorCode() { return errorCode; }
}
