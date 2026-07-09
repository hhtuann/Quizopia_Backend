package com.hhtuann.backend.classroom.domain.model;

/**
 * Classroom lifecycle status. Only ACTIVE classrooms are usable for session
 * visibility assignment; ARCHIVED is a soft-delete (members/junction rows kept
 * for historical attempts, but the classroom is excluded from new assignments).
 */
public enum ClassroomStatus {
    ACTIVE,
    ARCHIVED
}
