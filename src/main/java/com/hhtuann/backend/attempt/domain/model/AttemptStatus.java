package com.hhtuann.backend.attempt.domain.model;

/**
 * Attempt lifecycle status (V9 {@code attempts.status}).
 *
 * <p>Day 7 reachable: {@link #IN_PROGRESS} (start), {@link #SUBMITTED} (submit).
 * {@link #GRADED} is reserved for Day 8 grading ({@code SUBMITTED -> GRADED}).
 * The legacy enum values ({@code CREATED}, {@code AUTO_SUBMITTED}, {@code CANCELLED})
 * are intentionally excluded for the MVP.
 */
public enum AttemptStatus {
    IN_PROGRESS,
    SUBMITTED,
    GRADED
}
