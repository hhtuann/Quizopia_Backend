package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Response for {@code GET /api/attempts/{attemptId}/answer-history} (P4-A3,
 * issue #82): the accepted-answer history — exactly one row per
 * server-accepted autosave sequence (never for stale/retried saves).
 *
 * <p>Authorized readers: the attempt owner and the session-owner teacher.
 */
public record AnswerHistoryResponse(
        Long attemptId,
        int totalCount,
        List<AnswerHistoryItem> items) {

    public record AnswerHistoryItem(
            Long attemptQuestionId,
            long acceptedSequence,
            JsonNode answerPayload,
            Instant acceptedAt) {
    }
}
