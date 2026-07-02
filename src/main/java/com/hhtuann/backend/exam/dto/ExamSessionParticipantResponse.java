package com.hhtuann.backend.exam.dto;

import java.time.Instant;

public record ExamSessionParticipantResponse(
        Long id,
        Long studentProfileId,
        String studentCode,
        String displayName,
        String status,
        Instant addedAt,
        Instant blockedAt
) {
}
