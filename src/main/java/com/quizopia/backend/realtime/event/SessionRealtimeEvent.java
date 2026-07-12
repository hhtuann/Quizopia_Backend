package com.quizopia.backend.realtime.event;

import java.time.Instant;

/**
 * Internal domain event published by {@code ExamSessionService.openSession} ({@link RealtimeEventType#SESSION_OPENED})
 * and by {@code closeSession}/{@code getSessionDetail} lazy-close ({@link RealtimeEventType#SESSION_CLOSED})
 * <b>within</b> the business transaction, only on a real state transition. The
 * {@link RealtimeEventBroadcaster} delivers the STOMP message {@code AFTER_COMMIT} only.
 *
 * <p>Bulk list lazy-close does NOT publish this event (documented MVP exception — the bulk UPDATE
 * has no session IDs).
 */
public record SessionRealtimeEvent(
        RealtimeEventType type,
        Long sessionId,
        Instant occurredAt) {
}
