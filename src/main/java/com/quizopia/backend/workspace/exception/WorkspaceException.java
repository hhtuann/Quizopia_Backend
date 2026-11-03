package com.quizopia.backend.workspace.exception;

public class WorkspaceException extends RuntimeException {
    private final WorkspaceErrorCode errorCode;

    public WorkspaceException(WorkspaceErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public WorkspaceException(WorkspaceErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkspaceErrorCode getErrorCode() { return errorCode; }
}
