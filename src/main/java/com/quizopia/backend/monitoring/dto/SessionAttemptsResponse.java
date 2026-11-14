package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/exam-sessions/{sessionId}/attempts} (P4-A4,
 * issue #83): every attempt of the session — including IN_PROGRESS ones the
 * graded best-results report does not cover — so the teacher Activity panel
 * can monitor a live attempt. Authorized to the session-owner teacher only
 * (the STOMP topic gate); learner identity is the roster-resolvable userId.
 */
public record SessionAttemptsResponse(
        Long sessionId,
        List<Item> attempts) {

    public record Item(
            Long attemptId,
            Long userId,
            int attemptNumber,
            String status,
            Instant startedAt,
            Instant deadlineAt,
            Instant submittedAt) {
    }
}
