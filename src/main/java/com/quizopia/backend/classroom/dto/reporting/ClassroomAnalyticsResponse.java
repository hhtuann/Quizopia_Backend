package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * CLASS-M3 (issue #54): class analytics. Without {@code sessionId}: class
 * overview KPIs + per-session trend (latest window-begun sessions,
 * chronological). With {@code sessionId}: single-session detail — cohort
 * counters, BEST-percentage average/min/max/median and the 10-bucket
 * distribution (buckets i = [i·10,(i+1)·10), bucket 9 = [90,100] — the
 * same rule as {@code SessionStatisticsService}).
 */
public record ClassroomAnalyticsResponse(
        Long sessionId,
        String sessionTitle,
        String sessionCode,
        String sessionStatus,
        Instant startsAt,
        long currentRosterSize,
        long assignedSessionCount,
        long cohortSize,
        long startedCount,
        long submittedCount,
        long gradedCount,
        long notAttemptedCount,
        BigDecimal completionPercentage,
        BigDecimal averagePercentage,
        BigDecimal minimumPercentage,
        BigDecimal maximumPercentage,
        BigDecimal medianPercentage,
        List<Long> distributionBuckets,
        BigDecimal overallCompletionPercentage,
        List<SessionAnalyticsTrend> sessions,
        String historyCoverage) {

    public record SessionAnalyticsTrend(
            Long sessionId,
            String code,
            String title,
            String status,
            Instant startsAt,
            long cohortSize,
            long startedCount,
            long submittedCount,
            long gradedCount,
            BigDecimal completionPercentage,
            BigDecimal averagePercentage) {
    }
}
