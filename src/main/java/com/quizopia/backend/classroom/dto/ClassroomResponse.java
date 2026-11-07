package com.quizopia.backend.classroom.dto;

import java.time.Instant;

/**
 * Classroom list/create/update response (no member roster — use the detail
 * endpoint for members). The classroom belongs directly to its owner user.
 */
public record ClassroomResponse(
        Long id,
        String code,
        String name,
        String description,
        String status,
        long memberCount,
        Long ownerUserId,
        Instant createdAt
) {
}
