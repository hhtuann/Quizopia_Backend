package com.quizopia.backend.monitoring.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /internal/ai/v1/detections} (P4-C,
 * CONTRACT §3.3): the transport-agnostic worker path. No worker is
 * deployed in P4-C — the endpoint exists, is authenticated by
 * {@code X-Api-Key}, and is the documented integration point for the
 * future server-side inference worker.
 *
 * <p>Unlike the student path (attemptId from the path, owner from the
 * JWT), every item carries {@code attemptId} + {@code userId} explicitly
 * — the worker has no student JWT. The ingest core is identical
 * (ownership, IN_PROGRESS gate, media binding, time window,
 * idempotency); provenance is pinned to WORKER_VERIFIED and the runtime
 * must be WORKER_ORT.
 */
public record AiWorkerDetectionBatchRequest(
        @NotNull @Size(min = 1) List<Item> detections) {

    public record Item(
            @NotNull Long attemptId,
            @NotNull Long userId,
            AiDetectionBatchRequest.Item detection) {
    }
}
