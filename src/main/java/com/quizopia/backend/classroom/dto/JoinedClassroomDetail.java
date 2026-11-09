package com.quizopia.backend.classroom.dto;

import java.time.Instant;

/**
 * CLASS-M2 (issue #52): learner-safe detail of ONE joined classroom
 * ({@code GET /api/classrooms/joined/{classId}}) — accessible only to a
 * CURRENT member. Non-sensitive class metadata + the owner teacher's
 * DISPLAY name + membership time; the session count covers learner-visible
 * assigned sessions (DRAFT excluded).
 */
public record JoinedClassroomDetail(
        Long classroomId,
        String code,
        String name,
        String description,
        String status,
        String teacherDisplayName,
        Instant joinedAt,
        long assignedSessionCount
) {
}
