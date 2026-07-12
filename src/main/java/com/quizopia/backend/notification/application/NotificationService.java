package com.quizopia.backend.notification.application;

import com.quizopia.backend.notification.domain.model.Notification;
import com.quizopia.backend.notification.domain.model.NotificationType;
import com.quizopia.backend.notification.dto.NotificationListResponse;
import com.quizopia.backend.notification.dto.NotificationResponse;
import com.quizopia.backend.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public NotificationService(NotificationRepository repository,
                               SimpMessagingTemplate messaging,
                               Clock clock) {
        this.repository = repository;
        this.messaging = messaging;
        this.clock = clock;
    }

    /**
     * Creates a notification for a user, persists it, and pushes it via WebSocket
     * to /user/{userId}/queue/notifications. The WS push is best-effort (user
     * may not be connected — the FE polling fallback covers that).
     */
    @Transactional
    public void create(Long userId, NotificationType type, String title, String message, String link) {
        Notification saved = repository.saveAndFlush(
                new Notification(userId, type, title, message, link));
        push(saved);
    }

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(Long userId, int page, int size) {
        Page<Notification> result = repository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(page, Math.min(size, 50)));
        return new NotificationListResponse(
                result.map(this::toResponse).toList(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        repository.findById(notificationId).ifPresent(n -> {
            if (n.getUserId().equals(userId) && n.getReadAt() == null) {
                n.setReadAt(Instant.now(clock));
                repository.saveAndFlush(n);
            }
        });
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, Instant.now(clock));
    }

    private void push(Notification n) {
        try {
            messaging.convertAndSendToUser(
                    String.valueOf(n.getUserId()),
                    "/queue/notifications",
                    toResponse(n));
        } catch (Exception e) {
            log.warn("WebSocket push failed for user {} notification {}: {}",
                    n.getUserId(), n.getId(), e.getMessage());
        }
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                n.getLink(), n.getReadAt(), n.getCreatedAt());
    }
}
