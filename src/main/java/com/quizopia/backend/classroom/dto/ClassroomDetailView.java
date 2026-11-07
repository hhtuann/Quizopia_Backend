package com.quizopia.backend.classroom.dto;

import java.time.Instant;
import java.util.List;

/**
 * Classroom detail (GET /api/classrooms/{id}) — classroom fields + the full
 * member roster (small N per classroom; for large rosters use the paginated
 * members endpoint). Members are platform users (user-based membership).
 */
public record ClassroomDetailView(
        Long id,
        String code,
        String name,
        String description,
        String status,
        Long ownerUserId,
        Instant createdAt,
        long memberCount,
        List<MemberView> members
) {

    public record MemberView(
            Long userId,
            String displayName,
            String username,
            String email,
            Instant addedAt
    ) {
    }
}
