package com.quizopia.backend.exam.dto;

import java.time.Instant;

/**
 * CLASS-M2 (issue #52): one assigned session row on the TEACHER's
 * classroom-scoped session list ({@code GET /api/classrooms/{classId}/sessions}).
 * All lifecycle states are visible to the owner; per-class completion counts
 * are intentionally omitted (issue rule 8 — aggregate semantics deferred).
 */
public record ClassroomSessionItem(
        Long sessionId,
        String code,
        String title,
        String status,
        String visibility,
        Instant startsAt,
        Instant endsAt,
        Long examId,
        String examTitle
) {
}
