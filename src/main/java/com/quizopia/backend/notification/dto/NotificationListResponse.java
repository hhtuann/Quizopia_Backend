package com.quizopia.backend.notification.dto;

import java.util.List;

public record NotificationListResponse(
        List<NotificationResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
