package com.quizopia.backend.exam.dto;

import java.time.Instant;

/**
 * CLASS-M2 (issue #52): one OWN session eligible for assignment to a given
 * classroom ({@code GET /api/classrooms/{classId}/sessions/assignable}) —
 * caller-created, CLASS_RESTRICTED, DRAFT/SCHEDULED, PUBLISHED exam, and NOT
 * yet assigned to this classroom (duplicates excluded by the query).
 */
public record AssignableSessionItem(
        Long sessionId,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        Long examId,
        String examTitle
) {
}
