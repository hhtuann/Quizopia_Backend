package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CLASS-M3 (issue #54): one session column of the class gradebook — session
 * metadata plus the class-cohort summary computed from the SAME cohort query
 * as the matrix cells (see
 * {@code com.quizopia.backend.classroom.application.ClassroomGradebookService}).
 *
 * <p>{@code windowState} documents the taking-window evaluation used for the
 * cohort: {@code UPCOMING} (prospective — current roster, not a completed
 * denominator), {@code WINDOW} (window begun/ended — period-overlap cohort),
 * {@code NOT_HELD} (expired without ever opening — empty cohort),
 * {@code DANCELLED}/{@code DRAFT} as-is.</p>
 */
public record GradebookSessionColumn(
        Long id,
        String code,
        String title,
        String status,
        Instant startsAt,
        Instant endsAt,
        String windowState,
        Long examId,
        String examTitle,
        long cohortSize,
        long startedCount,
        long submittedCount,
        long gradedCount,
        BigDecimal completionPercentage,
        BigDecimal averagePercentage) {
}
