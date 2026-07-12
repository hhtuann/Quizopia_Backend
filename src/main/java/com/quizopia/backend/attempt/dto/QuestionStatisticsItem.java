package com.quizopia.backend.attempt.dto;

import java.math.BigDecimal;

/** Per-question statistics for a session (from BEST attempt GradeItems). */
public record QuestionStatisticsItem(
        Long examQuestionId,
        String questionType,
        BigDecimal maxScore,
        int answeredCount,
        int correctCount,
        int incorrectCount,
        int unansweredCount,
        BigDecimal correctRate,
        BigDecimal averageAwardedScore) {
}
