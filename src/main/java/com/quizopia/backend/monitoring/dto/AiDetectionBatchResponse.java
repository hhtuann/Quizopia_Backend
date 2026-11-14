package com.quizopia.backend.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code POST /api/attempts/{attemptId}/ai-detections} and
 * {@code POST /internal/ai/v1/detections} (P4-C, CONTRACT §3.1):
 * per-event ingest outcome.
 *
 * <p>{@code ACCEPTED} — stored, aggregated. {@code DUPLICATE} — the
 * eventId was already seen (retried batch); never re-alerts.
 * {@code QUARANTINED_LATE} — stored and flagged, but bound to a finished
 * attempt or outside the attempt time window; NEVER aggregates, NEVER
 * alerts. {@code REJECTED} — not stored (no active media binding, or a
 * worker item that failed attempt resolution/ownership). Validation
 * problems reject the whole batch with 400 before any insert (atomic
 * validation semantics, mirroring P4-A).
 */
public record AiDetectionBatchResponse(
        int acceptedCount,
        int duplicateCount,
        int quarantinedCount,
        int rejectedCount,
        List<ItemResult> results) {

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        QUARANTINED_LATE,
        REJECTED
    }

    /** Machine-readable reasons for QUARANTINED_LATE / REJECTED items. */
    public static final String REASON_NO_ACTIVE_MEDIA = "NO_ACTIVE_MEDIA";
    public static final String REASON_ATTEMPT_NOT_FOUND = "ATTEMPT_NOT_FOUND";
    public static final String REASON_NOT_ATTEMPT_OWNER = "NOT_ATTEMPT_OWNER";

    public record ItemResult(
            UUID eventId,
            Status status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

        public static ItemResult of(UUID eventId, Status status) {
            return new ItemResult(eventId, status, null);
        }

        public static ItemResult of(UUID eventId, Status status, String reason) {
            return new ItemResult(eventId, status, reason);
        }
    }
}
