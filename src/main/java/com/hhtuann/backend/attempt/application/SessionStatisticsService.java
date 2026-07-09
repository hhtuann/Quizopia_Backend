package com.hhtuann.backend.attempt.application;

import com.hhtuann.backend.attempt.dto.QuestionStatisticsItem;
import com.hhtuann.backend.attempt.dto.ScoreDistributionBucket;
import com.hhtuann.backend.attempt.dto.SessionResultItem;
import com.hhtuann.backend.attempt.dto.SessionStatisticsResponse;
import com.hhtuann.backend.grading.GradingErrorCode;
import com.hhtuann.backend.grading.GradingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 8 session statistics — computes from BEST results only (reuses
 * SessionResultService's CTE + window
 * query). BigDecimal-only arithmetic; no re-grade; no answer-key access.
 */
@Service
@Transactional(readOnly = true)
public class SessionStatisticsService {

    private final SessionResultService sessionResultService;
    private final JdbcTemplate jdbc;

    public SessionStatisticsService(SessionResultService sessionResultService, JdbcTemplate jdbc) {
        this.sessionResultService = sessionResultService;
        this.jdbc = jdbc;
    }

    @SuppressWarnings("null")
    public SessionStatisticsResponse getStatistics(Long userId, String primaryRole, Long sessionId) {
        sessionResultService.authorizeSessionAccess(userId, primaryRole, sessionId);
        List<SessionResultItem> best = sessionResultService.queryBestResults(sessionId);

        // Counts
        int started = countDistinct("SELECT count(DISTINCT student_profile_id) FROM attempts WHERE exam_session_id = ?",
                sessionId);
        int submitted = countDistinct(
                "SELECT count(DISTINCT student_profile_id) FROM attempts WHERE exam_session_id = ? AND status IN ('SUBMITTED','GRADED')",
                sessionId);
        int totalAttempt = countDistinct(
                "SELECT count(*) FROM attempts WHERE exam_session_id = ? AND status IN ('SUBMITTED','GRADED')",
                sessionId);
        int eligible = countDistinct(
                "SELECT count(*) FROM exam_session_participants WHERE exam_session_id = ? AND status = 'ELIGIBLE'",
                sessionId);
        int gradedStudentCount = best.size();
        int bestResultCount = best.size();

        // Score/percentage aggregates from BEST items
        List<BigDecimal> percentages = best.stream()
                .map(SessionResultItem::percentage)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        List<BigDecimal> scores = best.stream()
                .map(SessionResultItem::score)
                .filter(java.util.Objects::nonNull)
                .toList();

        BigDecimal averageScore = average(scores);
        BigDecimal averagePercentage = average(percentages);
        BigDecimal minimumScore = scores.isEmpty() ? null : scores.stream().min(Comparator.naturalOrder()).orElse(null);
        BigDecimal maximumScore = scores.isEmpty() ? null : scores.stream().max(Comparator.naturalOrder()).orElse(null);
        BigDecimal medianPercentage = median(percentages);

        // Distribution (10 buckets)
        List<ScoreDistributionBucket> distribution = computeDistribution(percentages);

        // Per-question statistics from BEST attempt GradeItems
        List<QuestionStatisticsItem> perQuestion = computePerQuestion(best, sessionId);

        // No pass threshold in the domain → null
        Integer passCount = null;
        BigDecimal passRate = null;

        return new SessionStatisticsResponse(sessionId,
                eligible > 0 ? eligible : null,
                started, submitted, gradedStudentCount,
                eligible > 0 ? Math.max(0, eligible - submitted) : null,
                totalAttempt, bestResultCount,
                averageScore, averagePercentage, minimumScore, maximumScore, medianPercentage,
                passCount, passRate, distribution, perQuestion);
    }

    private int countDistinct(String sql, Long sessionId) {
        Integer c = jdbc.queryForObject(sql, Integer.class, sessionId);
        return c != null ? c : 0;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty())
            return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values)
            sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n == 0)
            return null;
        if (n % 2 == 1)
            return sorted.get(n / 2).setScale(2, RoundingMode.HALF_UP);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static List<ScoreDistributionBucket> computeDistribution(List<BigDecimal> percentages) {
        int[] counts = new int[10];
        for (BigDecimal pct : percentages) {
            int idx = bucketIndex(pct);
            counts[idx]++;
        }
        List<ScoreDistributionBucket> buckets = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            buckets.add(new ScoreDistributionBucket(i * 10, (i + 1) * 10, i == 9, counts[i]));
        }
        return buckets;
    }

    private static int bucketIndex(BigDecimal percentage) {
        for (int i = 0; i < 9; i++) {
            if (percentage.compareTo(BigDecimal.valueOf(i * 10)) >= 0
                    && percentage.compareTo(BigDecimal.valueOf((i + 1) * 10)) < 0)
                return i;
        }
        if (percentage.compareTo(new BigDecimal("90")) >= 0
                && percentage.compareTo(new BigDecimal("100")) <= 0)
            return 9;
        throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "percentage out of range");
    }

    @SuppressWarnings("null")
    private List<QuestionStatisticsItem> computePerQuestion(List<SessionResultItem> best, Long sessionId) {
        if (best.isEmpty())
            return List.of();
        List<Long> bestAttemptIds = best.stream().map(SessionResultItem::bestAttemptId).toList();
        // Batch query GradeItems + AttemptQuestions + AttemptAnswers for all BEST
        // attempts
        // Group by exam_question_id, aggregate.
        var placeholders = bestAttemptIds.stream().map(x -> "?").reduce((a, b) -> a + "," + b).orElse("");
        String sql = """
                SELECT aq.exam_question_id, aq.question_type, aq.default_points,
                       gi.awarded_points, gi.is_correct,
                       aa.answer_payload IS NOT NULL AS answered
                FROM grade_items gi
                JOIN attempt_questions aq ON aq.id = gi.attempt_question_id
                LEFT JOIN attempt_answers aa ON aa.attempt_question_id = gi.attempt_question_id AND aa.attempt_id = gi.attempt_id
                WHERE gi.attempt_id IN (%s)
                ORDER BY aq.exam_question_id
                """
                .formatted(placeholders);
        var rows = jdbc.query(sql, (rs, n) -> new Object[] {
                rs.getLong("exam_question_id"),
                rs.getString("question_type"),
                rs.getBigDecimal("default_points"),
                rs.getBigDecimal("awarded_points"),
                rs.getBoolean("is_correct"),
                rs.getBoolean("answered")
        }, bestAttemptIds.toArray());

        // Group by exam_question_id
        Map<Long, List<Object[]>> byQuestion = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byQuestion.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add(row);
        }
        int bestCount = best.size();
        List<QuestionStatisticsItem> result = new ArrayList<>();
        for (var entry : byQuestion.entrySet()) {
            List<Object[]> qRows = entry.getValue();
            int answered = 0, correct = 0;
            BigDecimal awardedSum = BigDecimal.ZERO;
            for (Object[] r : qRows) {
                boolean ans = (boolean) r[5];
                boolean cor = (boolean) r[4];
                if (ans) {
                    answered++;
                    if (cor)
                        correct++;
                }
                awardedSum = awardedSum.add((BigDecimal) r[3]);
            }
            int incorrect = answered - correct;
            int unanswered = bestCount - answered;
            BigDecimal maxScore = qRows.isEmpty() ? BigDecimal.ONE : (BigDecimal) qRows.get(0)[2];
            BigDecimal correctRate = answered > 0
                    ? BigDecimal.valueOf(correct).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(answered),
                            2, RoundingMode.HALF_UP)
                    : null;
            BigDecimal avgAwarded = bestCount > 0
                    ? awardedSum.divide(BigDecimal.valueOf(bestCount), 2, RoundingMode.HALF_UP)
                    : null;
            result.add(new QuestionStatisticsItem(entry.getKey(), (String) qRows.get(0)[1], maxScore,
                    answered, correct, incorrect, unanswered, correctRate, avgAwarded));
        }
        return result;
    }
}
