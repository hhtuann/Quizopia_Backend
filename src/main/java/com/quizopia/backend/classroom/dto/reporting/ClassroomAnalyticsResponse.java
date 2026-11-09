package com.quizopia.backend.classroom.dto.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * CLASS-M3 (issue #54): class analytics.
 *
 * <h2>Nullability contract (Final Gate P2-01/P2-02)</h2>
 * <ul>
 *   <li>Single-session detail ({@code sessionId} present): {@code windowState}
 *       distinguishes states. {@code completionPercentage} and
 *       {@code notAttemptedCount} are completed-session metrics — non-null
 *       ONLY for {@code WINDOW} sessions (completion null at zero cohort);
 *       null for UPCOMING (prospective roster — nobody has "not attempted"
 *       an unopened assignment), DRAFT, CANCELLED and NOT_HELD. Raw
 *       started/submitted/graded counters are always exact.</li>
 *   <li>Overview ({@code sessionId} absent): {@code overallCompletionPercentage}
 *       aggregates over ALL eligible WINDOW sessions of the class (never
 *       sampled by the ≤20-session trend bound) = completedPairs /
 *       eligiblePairs; null when there are no eligible pairs. The pair
 *       fields are the transparent (session, learner) denominators — a
 *       learner counts once per session and correctly across sessions.</li>
 * </ul>
 * Distribution buckets: i = [i·10,(i+1)·10), bucket 9 = [90,100] (same rule
 * as {@code SessionStatisticsService}).
 */
public record ClassroomAnalyticsResponse(
        Long sessionId,
        String sessionTitle,
        String sessionCode,
        String sessionStatus,
        String windowState,
        Instant startsAt,
        long currentRosterSize,
        long assignedSessionCount,
        long cohortSize,
        long startedCount,
        long submittedCount,
        long gradedCount,
        Long notAttemptedCount,
        BigDecimal completionPercentage,
        BigDecimal averagePercentage,
        BigDecimal minimumPercentage,
        BigDecimal maximumPercentage,
        BigDecimal medianPercentage,
        List<Long> distributionBuckets,
        BigDecimal overallCompletionPercentage,
        Long eligiblePairs,
        Long completedPairs,
        List<SessionAnalyticsTrend> sessions,
        String historyCoverage) {

    public record SessionAnalyticsTrend(
            Long sessionId,
            String code,
            String title,
            String status,
            String windowState,
            Instant startsAt,
            long cohortSize,
            long startedCount,
            long submittedCount,
            long gradedCount,
            BigDecimal completionPercentage,
            BigDecimal averagePercentage) {
    }
}
