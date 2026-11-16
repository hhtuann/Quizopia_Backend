package com.quizopia.backend.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Response for {@code GET /api/exam-sessions/{sessionId}/ai-alerts}
 * (P4-C, CONTRACT §5): teacher-only, cursor-paged newest-first alert
 * backfill. The cursor is the last alertId of the page; the durable REST
 * feed is the source of truth on reconnect (the STOMP activity ping only
 * triggers a refetch).
 *
 * <p>Every item is an <b>observation</b> (CONTRACT §8): confidence is the
 * model's, never a misconduct verdict; {@code latestDetections} carries
 * the normalized bbox boxes of the last aggregated frame — no pixels, no
 * frames (evidence media is P4-D).
 */
public record AiAlertPageResponse(
        Long sessionId,
        List<Item> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {

    public record Item(
            Long alertId,
            Long attemptId,
            Long studentUserId,
            String studentName,
            String alertType,
            String status,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            int detectionCount,
            @JsonInclude(JsonInclude.Include.NON_NULL) Double maxConfidence,
            String modelId,
            String modelVersion,
            String provenance,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode latestDetections,
            /** P4-E current human-review state (V42) — never a verdict. */
            String reviewStatus,
            int reviewVersion,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant reviewedAt) {
    }
}
