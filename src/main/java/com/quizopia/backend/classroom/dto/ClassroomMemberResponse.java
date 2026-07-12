package com.quizopia.backend.classroom.dto;

import java.time.Instant;

/** Paginated member row (GET /api/classrooms/{id}/members). */
public record ClassroomMemberResponse(
        Long id,
        Long studentProfileId,
        String studentCode,
        String displayName,
        Instant addedAt
) {
}
