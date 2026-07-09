package com.hhtuann.backend.classroom.dto;

import java.time.Instant;

/**
 * Classroom list/create/update response (no member roster — use the detail
 * endpoint for members).
 */
public record ClassroomResponse(
        Long id,
        String code,
        String name,
        String description,
        String status,
        long memberCount,
        Long ownerTeacherId,
        Instant createdAt
) {
}
