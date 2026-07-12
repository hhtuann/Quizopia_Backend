package com.quizopia.backend.attempt.dto;

import java.math.BigDecimal;

/**
 * Day 8 per-question grading result view (student-facing). Carries the grading OUTCOME only —
 * {@code correct} is the student's pass/fail, NOT the answer key. No answer-key fields.
 */
public record QuestionResultView(
        Long attemptQuestionId,
        Long examQuestionId,
        String questionType,
        BigDecimal awardedScore,
        BigDecimal maxScore,
        boolean correct,
        boolean answered) {
}
