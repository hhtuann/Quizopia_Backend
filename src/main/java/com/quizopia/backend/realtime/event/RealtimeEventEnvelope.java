package com.quizopia.backend.realtime.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The shared realtime-event envelope (frozen contract A9.6). Carries NO answer payload, answer key,
 * score, grade, studentCode, username, email, schoolId, submissionIdempotencyKey, or
 * clientInstanceId — the only domain identity fields are {@code userId} (primary, issue #4 P0-1)
 * and the legacy {@code studentProfileId} provenance.
 *
 * <p><b>Nullability per event:</b>
 * <ul>
 *   <li>{@code eventId} / {@code eventType} / {@code occurredAt} / {@code serverTime} — always present.</li>
 *   <li>{@code sessionId} — required on SESSION_*, ATTEMPT_*, ACTIVE_COUNT_CHANGED; null on SERVER_TIME_SYNC.</li>
 *   <li>{@code attemptId} / {@code userId} / {@code studentProfileId} — required on ATTEMPT_*;
 *       {@code userId} is the primary owner identity; {@code studentProfileId} may be null for
 *       public (profile-less) users; null elsewhere.</li>
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
        Long userId,
        Long studentProfileId,
        Instant occurredAt,
        Instant serverTime,
        Long activeCount) {

    /** Builds an envelope with a fresh event id and the given type/identity fields. */
    public static RealtimeEventEnvelope of(RealtimeEventType type, Long sessionId, Long attemptId,
                                          Long userId, Long studentProfileId, Instant occurredAt,
                                          Instant serverTime, Long activeCount) {
        return new RealtimeEventEnvelope(
                UUID.randomUUID().toString(), type.name(), sessionId, attemptId, userId, studentProfileId,
                occurredAt, serverTime, activeCount);
    }
}
