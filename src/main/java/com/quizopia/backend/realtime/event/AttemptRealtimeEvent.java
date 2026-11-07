package com.quizopia.backend.realtime.event;

import java.time.Instant;

/**
 * Internal domain event published by {@code AttemptService.startAttempt} ({@link RealtimeEventType#ATTEMPT_STARTED})
 * and {@code AttemptSubmitService.submitAttempt} ({@link RealtimeEventType#ATTEMPT_SUBMITTED}) <b>within</b> the
 * business transaction. The {@link RealtimeEventBroadcaster} only delivers the STOMP message
 * {@code AFTER_COMMIT} (a rolled-back transition publishes nothing).
 *
 * <p>User-based identity (issue #4 P0-1): {@code userId} is the attempt owner.
 * {@code studentProfileId} is a legacy always-null placeholder kept for
 * envelope-shape compatibility (the student_profiles table no longer exists).
 *
 * <p>Carries the business {@code occurredAt} instant (the single post-lock {@code now} of the transition);
 * {@code serverTime} is recomputed at dispatch time.
 */
public record AttemptRealtimeEvent(
        RealtimeEventType type,
        Long sessionId,
        Long attemptId,
        Long userId,
        Long studentProfileId,
        Instant occurredAt) {

    /** Transitional overload for callers that only have the legacy profile id. */
    public AttemptRealtimeEvent(RealtimeEventType type, Long sessionId, Long attemptId,
                                 Long studentProfileId, Instant occurredAt) {
        this(type, sessionId, attemptId, null, studentProfileId, occurredAt);
    }
}
