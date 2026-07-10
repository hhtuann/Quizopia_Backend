package com.hhtuann.backend.notification.api;

import com.hhtuann.backend.notification.application.NotificationService;
import com.hhtuann.backend.notification.dto.NotificationListResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    public NotificationListResponse getNotifications(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.getNotifications(Long.valueOf(jwt.getSubject()), page, size);
    }

    @GetMapping("/api/notifications/unread-count")
    public UnreadCountResponse getUnreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCountResponse(service.getUnreadCount(Long.valueOf(jwt.getSubject())));
    }

    @PutMapping("/api/notifications/{id}/read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.markRead(Long.valueOf(jwt.getSubject()), id);
    }

    @PutMapping("/api/notifications/read-all")
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(Long.valueOf(jwt.getSubject()));
    }

    public record UnreadCountResponse(long count) {}
}
