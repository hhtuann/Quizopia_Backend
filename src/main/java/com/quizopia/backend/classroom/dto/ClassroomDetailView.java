package com.quizopia.backend.classroom.dto;

import java.time.Instant;
import java.util.List;

/**
 * Classroom detail (GET /api/classrooms/{id}) — classroom fields + the full
 * member roster (small N per classroom; for large rosters use the paginated
 * members endpoint).
 */
public record ClassroomDetailView(
        Long id,
        String code,
        String name,
        String description,
        String status,
        Long ownerTeacherId,
        Instant createdAt,
        long memberCount,
        List<MemberView> members
) {

    public record MemberView(
            Long studentProfileId,
            String studentCode,
            String displayName,
            Instant addedAt
    ) {
    }
}
