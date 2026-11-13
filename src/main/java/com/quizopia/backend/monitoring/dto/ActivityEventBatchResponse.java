package com.quizopia.backend.monitoring.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for {@code POST /api/attempts/{attemptId}/activity-events}
 * (P4-A2, issue #81): per-event idempotency outcome.
 *
 * <p>{@code ACCEPTED} — stored. {@code DUPLICATE} — the eventId was already
 * seen (retried batch); NOT an error. Validation problems reject the whole
 * batch with 400 before any insert (atomic validation semantics).
 */
public record ActivityEventBatchResponse(
        int acceptedCount,
        int duplicateCount,
        List<ItemResult> results) {

    public enum Status {
        ACCEPTED,
        DUPLICATE
    }

    public record ItemResult(
            UUID eventId,
            Status status) {

        public static ItemResult of(UUID eventId, Status status) {
            return new ItemResult(eventId, status);
        }
    }
}
