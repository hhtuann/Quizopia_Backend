package com.quizopia.backend.classroom.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ClassroomErrorCode {
    CLASSROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "Classroom not found"),
    CLASSROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied to this classroom resource"),
    CLASSROOM_CODE_CONFLICT(HttpStatus.CONFLICT, "Classroom code already exists for this owner"),
    CLASSROOM_MEMBER_DUPLICATE(HttpStatus.CONFLICT, "Student is already a member of this classroom"),
    CLASSROOM_TEACHER_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "Teacher profile not found for the current user"),
    CLASSROOM_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Classroom request validation failed"),

    // ---------- Identity-M3 roster (issue #15) ----------
    ROSTER_NOT_FOUND(HttpStatus.NOT_FOUND, "Roster entry not found"),
    ROSTER_DUPLICATE_PENDING(HttpStatus.CONFLICT, "A pending roster entry already exists for this email in this classroom"),
    ROSTER_INVALID_STATE(HttpStatus.CONFLICT, "Roster entry is not in a state that allows this operation"),
    ROSTER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "You cannot accept this invitation");

    private final HttpStatusCode status;
    private final String defaultMessage;

    ClassroomErrorCode(HttpStatusCode status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatusCode status() { return status; }
    public int statusCode() { return status.value(); }
    public String code() { return name(); }
    public String defaultMessage() { return defaultMessage; }
}
