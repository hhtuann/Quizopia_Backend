package com.quizopia.backend.monitoring.event;

import java.time.Instant;

/**
 * In-transaction application event: one durable attempt-activity record was
 * (or a client batch was) written. The {@code AttemptActivityBroadcaster}
 * delivers the STOMP delta notification {@code AFTER_COMMIT} only — a
 * rolled-back write never notifies (P4-A4, issue #83).
 *
 * @param activityType      the durable {@code ActivityEventType} name
 * @param activitySource    CLIENT or SERVER
 * @param clientOccurredAt  client-claimed time (CLIENT events only; may be skewed)
 */
public record AttemptActivityRealtimeEvent(
        Long sessionId,
        Long attemptId,
        Long userId,
        String activityType,
        String activitySource,
        Instant clientOccurredAt,
        Instant occurredAt) {
}
