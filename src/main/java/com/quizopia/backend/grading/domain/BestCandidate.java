package com.quizopia.backend.grading.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Day 8 BEST-selection candidate — one submitted + graded attempt's summary, used by
 * {@link com.quizopia.backend.grading.BestResultComparator} to deterministically pick the best result
 * per (student, exam session).
 *
 * @param attemptId   the attempt's stable id (final tie-break).
 * @param percentage  0–100 (BigDecimal; may be null for legacy/ungraded — sorts last).
 * @param score       the final/raw score (BigDecimal; tie-break after percentage).
 * @param submittedAt when the attempt was submitted (tie-break: earlier wins).
 */
public record BestCandidate(Long attemptId, BigDecimal percentage, BigDecimal score, Instant submittedAt) {
}
