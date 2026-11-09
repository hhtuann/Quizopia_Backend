package com.quizopia.backend.classroom.dto;

import com.quizopia.backend.classroom.domain.model.ClassroomStatus;

import java.time.Instant;

/**
 * CLASS-M1 (issue #50): one row of the learner's joined-classroom list —
 * the projection of a CURRENT {@code classroom_members} row for the
 * authenticated user, regardless of how the membership originated
 * (teacher direct add, verified-email reconciliation or accepted
 * invitation). Only non-sensitive class metadata plus the owner's
 * DISPLAY name are exposed — never the owner's email/username.
 */
public record JoinedClassroomResponse(
        Long classroomId,
        String code,
        String name,
        String description,
        String status,
        String teacherDisplayName,
        Instant joinedAt
) {
    /** HQL constructor-projection variant (enum status -> name). */
    public JoinedClassroomResponse(Long classroomId, String code, String name,
            String description, ClassroomStatus status,
            String teacherDisplayName, Instant joinedAt) {
        this(classroomId, code, name, description,
                status == null ? null : status.name(), teacherDisplayName, joinedAt);
    }
}
