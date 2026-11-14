package com.quizopia.backend.realtime.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared realtime-event envelope (frozen contract A9.6). Carries NO answer payload, answer key,
 * score, grade, studentCode, username, email, schoolId, submissionIdempotencyKey, or
 * clientInstanceId — the only domain identity field is {@code userId} (primary, issue #4 P0-1);
 * the {@code studentProfileId} slot is a legacy placeholder (always null) kept for shape.
 *
 * <p><b>Nullability per event:</b>
 * <ul>
 *   <li>{@code eventId} / {@code eventType} / {@code occurredAt} / {@code serverTime} — always present.</li>
 *   <li>{@code sessionId} — required on SESSION_*, ATTEMPT_*, ACTIVE_COUNT_CHANGED; null on SERVER_TIME_SYNC.</li>
 *   <li>{@code attemptId} / {@code userId} — required on ATTEMPT_*;
 *       {@code userId} is the owner identity; {@code studentProfileId} is a legacy
 *       always-null placeholder; null elsewhere.</li>
 *   <li>{@code activeCount} — required on ACTIVE_COUNT_CHANGED; null/optional elsewhere.</li>
 *   <li>{@code activityType} / {@code activitySource} / {@code clientOccurredAt} —
 *       P4-A4 (#83) additive optional fields, present ONLY on
 *       {@code ATTEMPT_ACTIVITY} notifications (durable-attempt-activity delta
 *       pings). Legacy events serialize exactly as before
 *       ({@link JsonInclude.Include#NON_NULL} omits all three).</li>
 * </ul>
 * {@link JsonInclude.Include#NON_NULL} omits the null fields so each event carries only its contract fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeEventEnvelope(
        String eventId,
        String eventType,
        Long sessionId,
        Long attemptId,
        Long userId,
        Long studentProfileId,
        Instant occurredAt,
        Instant serverTime,
        Long activeCount,
        String activityType,
        String activitySource,
        Instant clientOccurredAt) {

    /** Backward-compatible 9-arg shape: the P4-A activity fields stay null. */
    public RealtimeEventEnvelope(
            String eventId, String eventType, Long sessionId, Long attemptId, Long userId,
            Long studentProfileId, Instant occurredAt, Instant serverTime, Long activeCount) {
        this(eventId, eventType, sessionId, attemptId, userId, studentProfileId,
                occurredAt, serverTime, activeCount, null, null, null);
    }

    /** Builds an envelope with a fresh event id and the given type/identity fields. */
    public static RealtimeEventEnvelope of(RealtimeEventType type, Long sessionId, Long attemptId,
                                          Long userId, Long studentProfileId, Instant occurredAt,
                                          Instant serverTime, Long activeCount) {
        return new RealtimeEventEnvelope(
                UUID.randomUUID().toString(), type.name(), sessionId, attemptId, userId, studentProfileId,
                occurredAt, serverTime, activeCount);
    }

    /**
     * P4-A4 (#83): ATTEMPT_ACTIVITY notification envelope. {@code activityType}
     * names the durable activity event; NO metadata, NO answer content — the
     * REST timeline stays the source of truth; clients refetch on receipt.
     */
    public static RealtimeEventEnvelope activity(Long sessionId, Long attemptId, Long userId,
            String activityType, String activitySource, Instant clientOccurredAt,
            Instant occurredAt, Instant serverTime) {
        return new RealtimeEventEnvelope(
                UUID.randomUUID().toString(), RealtimeEventType.ATTEMPT_ACTIVITY.name(),
                sessionId, attemptId, userId, null, occurredAt, serverTime, null,
                activityType, activitySource, clientOccurredAt);
    }
}
