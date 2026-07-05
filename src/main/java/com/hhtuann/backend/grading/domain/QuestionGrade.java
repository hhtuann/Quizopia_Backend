package com.hhtuann.backend.grading.domain;

import java.math.BigDecimal;

/**
 * Day 8 per-question grading result (pure value object). All-or-nothing per question: {@code awardedScore}
 * is either {@code maxScore} (correct) or 0 (incorrect/unanswered). {@code answered} is the student-input
 * presence flag (used by statistics for the unanswered bucket); it is independent of {@code correct}.
 *
 * @param awardedScore 0 or maxScore (scale 2).
 * @param maxScore     the configured question weight (scale 2, &gt; 0).
 * @param correct      true iff the answer exactly matched the server answer key.
 * @param answered     true iff the student provided a non-empty answer for this question.
 */
public record QuestionGrade(BigDecimal awardedScore, BigDecimal maxScore, boolean correct, boolean answered) {
}
