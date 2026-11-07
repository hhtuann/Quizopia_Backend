package com.quizopia.backend.classroom.dto;

import java.time.Instant;

/**
 * Classroom member row (user-based membership).
 */
public record ClassroomMemberResponse(
        Long id,
        Long userId,
        String displayName,
        Instant addedAt
) {
}
