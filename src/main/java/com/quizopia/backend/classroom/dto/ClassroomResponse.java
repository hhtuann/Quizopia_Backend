package com.quizopia.backend.classroom.dto;

import java.time.Instant;

/**
 * Classroom list/create/update response (no member roster — use the detail
 * endpoint for members).
 *
 * <p>Identity-M3: {@code ownerUserId} is the user-based owner; null
 * {@code ownerTeacherId} marks an independent classroom.
 */
public record ClassroomResponse(
        Long id,
        String code,
        String name,
        String description,
        String status,
        long memberCount,
        Long ownerTeacherId,
        Long ownerUserId,
        Instant createdAt
) {
}
