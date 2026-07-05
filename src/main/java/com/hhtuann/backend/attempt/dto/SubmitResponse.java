package com.hhtuann.backend.attempt.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response for {@code POST /api/attempts/{attemptId}/submit} — Day 8 extends Day 7 with the auto-grading
 * summary ({@code score}/{@code maxScore}/{@code percentage}).
 *
 * <p>Returned for BOTH a first submit and an {@code IMMUTABLE_CACHED_RESPONSE} same-key retry (the retry
 * deserializes the cached JSON verbatim — {@code submittedAt}/{@code serverTime} and the grading summary are
 * the original frozen values, never recomputed). The grading summary is computed once, in the submit
 * transaction, and persisted as an immutable {@code Grade}; the cached response carries that snapshot.
 *
 * <p><b>DATA-LEAK SAFETY:</b> carries NO answers, answer key, correctOptionIds, expectedNumericAnswer,
 * grade id, clientInstanceId, or submissionIdempotencyKey. {@code score}/{@code maxScore}/{@code percentage}
 * are the student's own auto-grading summary only.
 *
 * @param attemptId      the submitted attempt.
 * @param status         always {@code SUBMITTED} on Day 7/8.
 * @param submittedAt    frozen at first submit.
 * @param serverTime     frozen at first submit.
 * @param attemptNumber  the attempt's 1-based number within (session, student).
 * @param sessionId      the exam session (Day 8).
 * @param score          auto-grading score (BigDecimal, scale 2). Null on legacy/Day-7 cached responses.
 * @param maxScore       auto-grading max (BigDecimal, scale 2). Null on legacy cached responses.
 * @param percentage     0–100 (scale 2, HALF_UP). Null on legacy cached responses.
 */
public record SubmitResponse(
        Long attemptId,
        String status,
        Instant submittedAt,
        Instant serverTime,
        Integer attemptNumber,
        Long sessionId,
        BigDecimal score,
        BigDecimal maxScore,
        BigDecimal percentage) {
}
