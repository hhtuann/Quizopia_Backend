package com.quizopia.backend.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/attempts/{attemptId}/activity-events}
 * (P4-A2, issue #81). Bounded batch of client-observed events.
 *
 * <p>Contract highlights:
 * <ul>
 *   <li>{@code eventId} — client-generated UUID; the idempotency identity
 *       (retried batches re-insert nothing);</li>
 *   <li>{@code eventType} — must be one of the CLIENT-observable P4-A types
 *       (server types like ANSWER_ACCEPTED are rejected here — a client can
 *       never forge a server-confirmed record);</li>
 *   <li>{@code clientOccurredAt} / {@code clientSequence} — REQUIRED; stored
 *       verbatim (clock skew and out-of-order delivery are retained
 *       observations, never "corrected"); a sanity window (2000-01-01 …
 *       2200-01-01) rejects garbage timestamps;</li>
 *   <li>{@code metadata} — optional small object ({@code <=2KB} serialized);
 *       MUST NOT carry answer payloads, tokens or secrets (enforced by
 *       convention + size bound; the canonical answer path is Autosave).</li>
 * </ul>
 * The batch-size MAX is enforced by the service from
 * {@code quizopia.monitoring.activity-ingest.max-batch-size} (review REV-6) —
 * only non-emptiness is validated here, so the configured bound is the single
 * source of truth and oversized batches get the specific
 * {@code MONITORING_BATCH_TOO_LARGE} error.
 */
public record ActivityEventBatchRequest(
        @NotNull @Size(min = 1) List<Item> events) {

    public record Item(
            @NotNull UUID eventId,
            @NotNull @Size(max = 40) String eventType,
            @NotNull Instant clientOccurredAt,
            @NotNull Long clientSequence,
            UUID clientInstanceId,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode metadata) {
    }
}
