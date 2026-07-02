package com.hhtuann.backend.exam.dto;

import java.time.Instant;

public record ExamSessionDetailResponse(
        Long id,
        Long examId,
        Integer examVersionNumber,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        Integer maxAttempts,
        Instant openedAt,
        Instant closedAt,
        long participantCount,
        Integer version,
        Instant createdAt
) {
}
