package com.hhtuann.backend.exam.exception;

public class ExamException extends RuntimeException {
    private final ExamErrorCode errorCode;

    public ExamException(ExamErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public ExamException(ExamErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ExamErrorCode getErrorCode() { return errorCode; }
}
