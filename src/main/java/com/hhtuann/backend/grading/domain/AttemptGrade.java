package com.hhtuann.backend.grading.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Day 8 attempt grading summary (pure value object): the aggregate of all per-question {@link QuestionGrade}s.
 *
 * <p>{@code score} = Σ awardedScore; {@code maxScore} = Σ maxScore; {@code percentage} =
 * {@code score × 100 / maxScore} at scale 2, {@code RoundingMode.HALF_UP}. All arithmetic is BigDecimal —
 * no double/float. A {@code maxScore} of 0 is an invalid exam configuration (rejected by the engine, not
 * represented here as NaN/Infinity).
 *
 * @param score      total awarded (scale 2).
 * @param maxScore   total configured (scale 2, &gt; 0).
 * @param percentage 0–100 (scale 2, HALF_UP); 100 for a perfect score.
 * @param items      the per-question results, in the order they were graded (stable).
 */
public record AttemptGrade(BigDecimal score, BigDecimal maxScore, BigDecimal percentage, List<QuestionGrade> items) {
}
