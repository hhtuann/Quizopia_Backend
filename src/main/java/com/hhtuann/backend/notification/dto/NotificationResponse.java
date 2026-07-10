package com.hhtuann.backend.notification.dto;

import com.hhtuann.backend.notification.domain.model.NotificationType;

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
