package com.hhtuann.backend.attempt.dto;

import java.time.Instant;
import java.util.List;

/**
 * Paginated response for {@code GET /api/attempts/my} — per frozen contract §8.3.
 *
 * <p><b>DATA-LEAK SAFETY:</b> items carry NO saved answers, questions, answerKey, correct option,
 * score, grade, student identity, or submission idempotency key. Sort is fixed
 * ({@code createdAt: DESC}); clients cannot supply arbitrary SQL sort.
 */
public record MyAttemptsResponse(
        List<MyAttemptListItem> items, int page, int size,
        long totalElements, int totalPages, String sort) {

    /** Fixed deterministic sort label (createdAt DESC, then attemptId DESC). */
    public static final String SORT = "createdAt: DESC";

    public record MyAttemptListItem(
            Long attemptId, Long sessionId, String sessionCode, String sessionTitle,
            Integer attemptNumber, String status, Instant startedAt, Instant submittedAt,
            Instant deadlineAt, Instant createdAt) {}
}
