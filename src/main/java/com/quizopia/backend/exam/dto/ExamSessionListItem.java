package com.quizopia.backend.exam.dto;

import java.time.Instant;

public record ExamSessionListItem(
        Long id,
        Long examId,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        Integer maxAttempts,
        long participantCount,
        Instant createdAt
) {
}
