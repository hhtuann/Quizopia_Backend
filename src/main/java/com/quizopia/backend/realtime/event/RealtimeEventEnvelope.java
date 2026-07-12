package com.quizopia.backend.realtime.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared realtime-event envelope (frozen contract §9.6). Carries NO answer payload, answer key,
 * score, grade, studentCode, username, email, userId, schoolId, submissionIdempotencyKey, or
 * clientInstanceId — the only domain identity field is {@code studentProfileId}.
 *
 * <p><b>Nullability per event:</b>
 * <ul>
 *   <li>{@code eventId} / {@code eventType} / {@code occurredAt} / {@code serverTime} — always present.</li>
 *   <li>{@code sessionId} — required on SESSION_*, ATTEMPT_*, ACTIVE_COUNT_CHANGED; null on SERVER_TIME_SYNC.</li>
 *   <li>{@code attemptId} / {@code studentProfileId} — required on ATTEMPT_*; null elsewhere.</li>
 *   <li>{@code activeCount} — required on ACTIVE_COUNT_CHANGED; null/optional elsewhere.</li>
 * </ul>
 * {@link JsonInclude.Include#NON_NULL} omits the null fields so each event carries only its contract fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeEventEnvelope(
        String eventId,
        String eventType,
        Long sessionId,
        Long attemptId,
        Long studentProfileId,
        Instant occurredAt,
        Instant serverTime,
        Long activeCount) {

    /** Builds an envelope with a fresh event id and the given type/identity fields. */
    public static RealtimeEventEnvelope of(RealtimeEventType type, Long sessionId, Long attemptId,
                                          Long studentProfileId, Instant occurredAt, Instant serverTime,
                                          Long activeCount) {
        return new RealtimeEventEnvelope(
                UUID.randomUUID().toString(), type.name(), sessionId, attemptId, studentProfileId,
                occurredAt, serverTime, activeCount);
    }
}
