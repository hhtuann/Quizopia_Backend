package com.quizopia.backend.attempt.dto;

/**
 * Request body for {@code POST /api/attempts/{attemptId}/submit} (idempotent submit) — per frozen
 * contract §7.1.
 *
 * <p>{@code submissionIdempotencyKey} is required: a non-blank JSON string of 1–100 characters with
 * no whitespace anywhere (leading/trailing/embedded all rejected). No trim, no case-folding, no
 * normalization — the exact client string is stored and compared. A UUID is recommended but not
 * required.
 */
public record SubmitRequest(String submissionIdempotencyKey) {
}
