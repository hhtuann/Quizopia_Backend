package com.quizopia.backend.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Response for {@code GET /api/attempts/{attemptId}/timeline} (P4-A4,
 * issue #83): one stable cursor-paged page of the durable unified timeline.
 *
 * <p>Items are ordered by (serverReceivedAt, id) ASCENDING — the stable
 * server chronology. Late-arriving events can only appear in LATER pages;
 * pages already served never change. Provenance is explicit: {@code source}
 * (CLIENT observation vs SERVER confirmation) plus BOTH timestamps so the
 * UI can label trust and display clock skew; the client timestamp is an
 * observation, never the ordering key.
 *
 * <p>{@code nextCursor} is null when the page is the last one. Repeating a
 * page request with the same cursor is deterministic (no drift). Metadata is
 * passed through verbatim (opaque object) so P4-B/C/D can extend event
 * payloads without breaking older readers.
 */
public record AttemptTimelineResponse(
        Long attemptId,
        List<TimelineItem> items,
        String nextCursor,
        Instant serverTime) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimelineItem(
            UUID eventId,
            String eventType,
            String source,
            Instant clientOccurredAt,
            Long clientSequence,
            Instant serverReceivedAt,
            JsonNode metadata) {
    }
}
