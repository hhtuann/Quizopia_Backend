package com.hhtuann.backend.realtime.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Delivers realtime events <b>AFTER_COMMIT</b> only ({@code fallbackExecution=false}): a rolled-back
 * transition publishes nothing and recomputes no active count. The active count is always read from
 * PostgreSQL via {@link RealtimeActiveCountService} (a separate bean so the {@code REQUIRES_NEW}
 * transaction is honored through the Spring proxy — not self-invoked).
 *
 * <p>For an attempt transition the broadcaster sends the attempt event first, then
 * {@link RealtimeEventType#ACTIVE_COUNT_CHANGED}; the two sends are independent so a failure of one
 * does not drop the other (failure-isolation). Outbound messaging failures are caught + logged
 * sanitized server-side: REST already committed and remains the source of truth; the broker exception
 * is never leaked to the REST client.
 */
@Component
public class RealtimeEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventBroadcaster.class);

    private final RealtimeActiveCountService activeCountService;
    private final RealtimePublisher publisher;

    public RealtimeEventBroadcaster(RealtimeActiveCountService activeCountService, RealtimePublisher publisher) {
        this.activeCountService = activeCountService;
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onAttemptEvent(AttemptRealtimeEvent event) {
        Long sessionId = event.sessionId();
        try {
            long count = activeCountService.countActiveAttempts(sessionId);
            // Attempt event first, then active-count event (stable order).
            sendSafe(sessionId, event.type(), event.attemptId(), event.studentProfileId(), event.occurredAt(), count);
            sendSafe(sessionId, RealtimeEventType.ACTIVE_COUNT_CHANGED, null, null, event.occurredAt(), count);
        } catch (RuntimeException e) {
            // Log the failure category only — NEVER e.toString()/message: the exception message can
            // echo a payload/token/SQL and must not leak to logs (B1R4-B §17). The stable type name +
            // event type + session id are sufficient for operations.
            log.warn("Realtime delivery failed for {} on session {} (transaction already committed; REST is source of truth): {}",
                    event.type(), sessionId, e.getClass().getName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onSessionEvent(SessionRealtimeEvent event) {
        sendSafe(event.sessionId(), event.type(), null, null, event.occurredAt(), null);
    }

    private void sendSafe(Long sessionId, RealtimeEventType type, Long attemptId, Long studentProfileId,
                          Instant occurredAt, Long activeCount) {
        try {
            publisher.sendToSessionTopic(sessionId, type, attemptId, studentProfileId, occurredAt, activeCount);
        } catch (RuntimeException e) {
            // Sanitized: class name only (the message could echo a payload/token/SQL — §17).
            log.warn("STOMP send failed for {} on session {} (isolated; continuing): {}", type, sessionId, e.getClass().getName());
        }
    }
}
