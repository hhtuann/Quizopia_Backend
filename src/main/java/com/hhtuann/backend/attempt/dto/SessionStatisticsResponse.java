package com.hhtuann.backend.attempt.dto;

import java.math.BigDecimal;
import java.util.List;

/** Day 8 session statistics (teacher/admin). Computed from BEST results only. */
public record SessionStatisticsResponse(
        Long examSessionId,
        Integer eligibleStudentCount,
        int startedStudentCount,
        int submittedStudentCount,
        int gradedStudentCount,
        Integer notSubmittedCount,
        int totalAttemptCount,
        int bestResultCount,
        BigDecimal averageScore,
        BigDecimal averagePercentage,
        BigDecimal minimumScore,
        BigDecimal maximumScore,
        BigDecimal medianPercentage,
        Integer passCount,
        BigDecimal passRate,
        List<ScoreDistributionBucket> distribution,
        List<QuestionStatisticsItem> perQuestionStatistics) {
}
