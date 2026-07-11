package com.hhtuann.backend.notification.domain.model;

public enum NotificationType {
    EXAM_SESSION_AVAILABLE,
    RESULT_GRADED,
    ADDED_TO_CLASS,
    SESSION_ENDED,
    IMPORT_COMPLETED,
    STUDENT_JOINED_CLASS,
    STUDENT_STARTED_EXAM,
    NEW_USER_REGISTERED,
    NEW_ACADEMIC_ACTIVITY,
    USER_STATUS_CHANGED,
    /** ACADEMIC_ADMIN approved a newly-registered student into a school (onboarding). */
    ADDED_TO_SCHOOL
}
