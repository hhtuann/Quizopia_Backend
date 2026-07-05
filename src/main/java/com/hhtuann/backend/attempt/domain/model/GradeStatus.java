package com.hhtuann.backend.attempt.domain.model;

/**
 * Grade status (V9 {@code grades.status}). Day 8 auto-grading produces
 * {@link #AUTO_GRADED}; the release flow transitions to {@link #RELEASED}
 * (which sets {@code released_at}). The legacy manual-grading values
 * ({@code PENDING}, {@code NEEDS_MANUAL_GRADING}) are excluded for the MVP
 * (all four question types are auto-gradable).
 */
public enum GradeStatus {
    AUTO_GRADED,
    RELEASED
}
