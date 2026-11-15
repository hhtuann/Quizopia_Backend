package com.quizopia.backend.monitoring.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/attempts/{attemptId}/ai-detections}
 * (P4-C, CONTRACT §3.1): a bounded batch of client-model detection
 * observations (browser ORT-Web).
 *
 * <p>Contract highlights:
 * <ul>
 *   <li>{@code eventId} — client-generated UUID; the idempotency identity
 *       (retried batches re-insert nothing and never re-alert);</li>
 *   <li>{@code eventType} — one of the four OBSERVED types; the word
 *       DETECTED is reserved for server-aggregated alerts;</li>
 *   <li>{@code observedAt} — frame capture time on the CLIENT clock,
 *       stored VERBATIM; only a wide sanity window (2000-01-01 …
 *       2200-01-01) rejects garbage — the tighter attempt-window binding
 *       is a per-item QUARANTINED_LATE outcome, never a silent
 *       correction;</li>
 *   <li>{@code detections} — only the boxes that drove the event (e.g.
 *       the ≥2 person boxes for MULTIPLE_PERSONS_OBSERVED), bbox values
 *       normalized 0..1 to frameWidth/frameHeight; no pixels, no frames
 *       (evidence media is P4-D).</li>
 * </ul>
 * Batch/detections size MAXes are enforced by the service from
 * {@code quizopia.monitoring.ai-ingest.*} (the ActivityEventBatchRequest
 * convention) so the configured bound stays the single source of truth
 * and oversized payloads get the specific MONITORING_BATCH_TOO_LARGE
 * error.
 */
public record AiDetectionBatchRequest(
        @NotNull @Size(min = 1) List<Item> detections) {

    public record Item(
            @NotNull UUID eventId,
            @NotNull @Size(max = 40) String eventType,
            @NotNull Instant observedAt,
            @NotNull Long sequence,
            @NotNull @NotBlank @Size(max = 40) String modelId,
            @NotNull @NotBlank @Size(max = 40) String modelVersion,
            @NotNull @Size(max = 20) String runtime,
            @NotNull @Min(1) Integer frameWidth,
            @NotNull @Min(1) Integer frameHeight,
            @NotNull @Min(0) Integer processingMs,
            List<DetectedObject> detections) {
    }

    /** One model output box that drove the event (CONTRACT §3.1). */
    public record DetectedObject(
            @NotNull Integer classId,
            @NotNull @Size(max = 32) String label,
            @NotNull @Min(0) @Max(1) Double confidence,
            @NotNull @Size(min = 4, max = 4) List<@Min(0) @Max(1) Double> bbox) {
    }
}
