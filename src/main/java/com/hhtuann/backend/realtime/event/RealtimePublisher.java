package com.hhtuann.backend.realtime.event;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Outbound STOMP delivery. Thin wrapper over {@link SimpMessagingTemplate} that builds the
 * {@link RealtimeEventEnvelope} and routes it to the frozen destinations:
 * <ul>
 *   <li>session events → {@code /topic/exam-sessions/{sessionId}};</li>
 *   <li>{@code SERVER_TIME_SYNC} → {@code /user/queue/attempt} (session-targeted — not principal fan-out).</li>
 * </ul>
 * All delivery is invoked <b>after</b> transaction commit by the broadcaster / time-sync listener —
 * this class never starts or holds a write transaction and never reads the database.
 */
@Component
public class RealtimePublisher {

    private final SimpMessagingTemplate messaging;
    private final Clock clock;

    public RealtimePublisher(SimpMessagingTemplate messaging, Clock clock) {
        this.messaging = messaging;
        this.clock = clock;
    }

    /** Sends a session-scoped event to {@code /topic/exam-sessions/{sessionId}}. */
    public void sendToSessionTopic(Long sessionId, RealtimeEventType type, Long attemptId,
                                   Long studentProfileId, Instant occurredAt, Long activeCount) {
        Instant serverTime = Instant.now(clock);
        messaging.convertAndSend("/topic/exam-sessions/" + sessionId,
                RealtimeEventEnvelope.of(type, sessionId, attemptId, studentProfileId, occurredAt, serverTime, activeCount));
    }

    /**
     * Sends {@code SERVER_TIME_SYNC} to ONE specific WebSocket session (not principal fan-out), using
     * <b>direct session routing</b> (B1R4-B2F7).
     *
     * <p>The {@code simpSessionId} is passed as BOTH the {@code user} argument AND the {@code simpSessionId}
     * header. Spring's {@code DefaultUserDestinationResolver.parseMessage} then takes its
     * {@code userName.equals(sessionId)} branch and resolves a single target for that session
     * <b>without consulting {@code SimpUserRegistry}</b>:
     * <pre>
     *   if (userName.equals(sessionId)) { sessionIds = singleton(sessionId); }   // registry-independent
     *   else { sessionIds = getSessionIdsByUser(userName, sessionId); }           // registry-dependent
     * </pre>
     * Routing by {@code principalName} instead would take the {@code else} branch and depend on
     * {@code DefaultSimpUserRegistry} having recorded the user + session before the listener resolves — a
     * state populated by the same {@code SessionSubscribeEvent} with no {@code @Order} guarantee, which
     * opened the B2F6 "0 messages" race under package load. Direct session routing eliminates that
     * dependency; if the same principal has other sessions, they do NOT receive this sync (no fan-out).
     * The client-facing destination remains {@code /user/queue/attempt}; payload/nullability are unchanged.
     */
    public void sendServerTimeSync(String simpSessionId) {
        Instant now = Instant.now(clock);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionId(simpSessionId);
        headers.setLeaveMutable(true);
        messaging.convertAndSendToUser(simpSessionId, "/queue/attempt",
                RealtimeEventEnvelope.of(RealtimeEventType.SERVER_TIME_SYNC, null, null, null, now, now, null),
                headers.getMessageHeaders());
    }
}
