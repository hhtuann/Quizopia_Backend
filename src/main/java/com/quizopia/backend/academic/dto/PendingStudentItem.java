package com.quizopia.backend.academic.dto;

import java.time.Instant;

public record PendingStudentItem(
        Long userId,
        String username,
        String email,
        String displayName,
        Instant registeredAt
) {
}
