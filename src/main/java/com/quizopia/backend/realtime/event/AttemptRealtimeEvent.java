package com.quizopia.backend.realtime.event;

import java.time.Instant;

/**
 * Internal domain event published by {@code AttemptService.startAttempt} ({@link RealtimeEventType#ATTEMPT_STARTED})
 * and {@code AttemptSubmitService.submitAttempt} ({@link RealtimeEventType#ATTEMPT_SUBMITTED}) <b>within</b> the
 * business transaction. The {@link RealtimeEventBroadcaster} only delivers the STOMP message
 * {@code AFTER_COMMIT} (a rolled-back transition publishes nothing).
 *
 * <p>Carries the business {@code occurredAt} instant (the single post-lock {@code now} of the transition);
 * {@code serverTime} is recomputed at dispatch time.
 */
public record AttemptRealtimeEvent(
        RealtimeEventType type,
        Long sessionId,
        Long attemptId,
        Long studentProfileId,
        Instant occurredAt) {
}
