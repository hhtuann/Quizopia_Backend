package com.quizopia.backend.workspace.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum WorkspaceErrorCode {
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Workspace not found"),
    WORKSPACE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this workspace"),
    WORKSPACE_ROLE_DENIED(HttpStatus.FORBIDDEN, "Your workspace role does not allow this action"),
    WORKSPACE_MEMBERSHIP_CONFLICT(HttpStatus.CONFLICT, "User is already a member of this workspace"),
    WORKSPACE_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Workspace request validation failed");

    private final HttpStatusCode status;
    private final String defaultMessage;

    WorkspaceErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() { return status; }
    public int statusCode() { return status.value(); }
    public String code() { return name(); }
    public String defaultMessage() { return defaultMessage; }
}
