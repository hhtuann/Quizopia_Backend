package com.quizopia.backend.notification.dto;

import com.quizopia.backend.notification.domain.model.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String message,
        String link,
        Instant readAt,
        Instant createdAt
) {}
