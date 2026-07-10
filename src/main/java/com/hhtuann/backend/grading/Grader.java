package com.hhtuann.backend.grading;

import com.hhtuann.backend.grading.domain.AttemptGrade;
import com.hhtuann.backend.grading.domain.QuestionGrade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Day 8 grading engine — pure, deterministic, no DB/Spring. Grades exactly the four question types
 * (SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL), all-or-nothing per question, and
 * aggregates the attempt summary.
 *
 * <p><b>Invariants (frozen Day 8 grading rules):</b>
 * <ul>
 *   <li>BigDecimal only — no {@code double}/{@code float} anywhere.</li>
 *   <li>NUMERIC_FILL correctness is an <b>exact String equality</b> on the raw expected vs submitted
 *       answer (so {@code "1.00"} != {@code "1"}).</li>
 *   <li>SINGLE/MULTIPLE/NUMERIC are all-or-nothing; TRUE_FALSE_MATRIX awards <b>proportional credit</b>
 *       (correct statements / total statements × maxScore), with {@code correct()==true} only when all
 *       statements are right.</li>
 *   <li>{@code percentage = score × 100 / maxScore}, scale 2, {@code RoundingMode.HALF_UP}.</li>
 *   <li>Set/matrix comparison is order-independent.</li>
 *   <li>Missing server answer key / non-positive max score → {@link GradingException} (fail closed, no silent 0).</li>
 * </ul>
 *
 * <p>This class holds only pure logic so it is exhaustively unit-testable without a Spring context. JSONB
 * answer-payload / answer-key parsing and DB batch loading live in the orchestration service (separate),
 * which calls these methods with parsed Java types.
 */
public final class Grader {

    /** Scale-2 zero (matches NUMERIC(10,2) awarded_score for incorrect/unanswered). */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Grader() {
    }

    // --- SINGLE_CHOICE: exactly one correct option key. ---

    public static QuestionGrade gradeSingle(BigDecimal maxScore, String correctKey, String selectedKey) {
        requirePositiveMaxScore(maxScore);
        if (correctKey == null || correctKey.isBlank()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "single-choice: missing correct option key");
        }
        if (selectedKey == null || selectedKey.isBlank()) {
            return new QuestionGrade(ZERO, maxScore, false, false);
        }
        boolean correct = selectedKey.equals(correctKey);
        return new QuestionGrade(correct ? maxScore : ZERO, maxScore, correct, true);
    }

    // --- MULTIPLE_CHOICE: exact set match (order-independent). ---

    public static QuestionGrade gradeMultiple(BigDecimal maxScore, Set<String> correctKeys, Set<String> selectedKeys) {
        requirePositiveMaxScore(maxScore);
        if (correctKeys == null || correctKeys.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "multiple-choice: missing correct option keys");
        }
        Set<String> selected = selectedKeys == null ? Set.of() : new HashSet<>(selectedKeys);
        boolean answered = !selected.isEmpty();
        boolean correct = selected.equals(correctKeys); // Set equality is order-independent and rejects extra/missing.
        return new QuestionGrade(correct ? maxScore : ZERO, maxScore, correct, answered);
    }

    // --- TRUE_FALSE_MATRIX: proportional credit — awarded = (correct statements / total) × maxScore. ---

    public static QuestionGrade gradeMatrix(BigDecimal maxScore, Map<String, Boolean> correct, Map<String, Boolean> student) {
        requirePositiveMaxScore(maxScore);
        if (correct == null || correct.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "true-false-matrix: missing answer key");
        }
        Map<String, Boolean> answered = student == null ? Map.of() : student;
        boolean present = !answered.isEmpty();
        int total = correct.size();
        int right = 0;
        for (Map.Entry<String, Boolean> entry : correct.entrySet()) {
            Boolean submitted = answered.get(entry.getKey());
            if (submitted != null && submitted.equals(entry.getValue())) {
                right++;
            }
        }
        boolean correctResult = (right == total);
        BigDecimal awarded = correctResult
                ? maxScore
                : maxScore.multiply(BigDecimal.valueOf(right)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new QuestionGrade(awarded, maxScore, correctResult, present);
    }

    // --- NUMERIC_FILL: exact String equality on the raw answer (1.00 != 1). ---

    public static QuestionGrade gradeNumeric(BigDecimal maxScore, String expected, String studentValue) {
        requirePositiveMaxScore(maxScore);
        if (expected == null || expected.isBlank()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "numeric-fill: missing expected answer");
        }
        if (studentValue == null) {
            return new QuestionGrade(ZERO, maxScore, false, false);
        }
        boolean correct = expected.equals(studentValue);
        return new QuestionGrade(correct ? maxScore : ZERO, maxScore, correct, true);
    }

    // --- Aggregation: exact BigDecimal sum + HALF_UP percentage. ---

    public static AttemptGrade aggregate(List<QuestionGrade> items) {
        if (items == null || items.isEmpty()) {
            throw new GradingException(GradingErrorCode.GRADING_DATA_INCONSISTENT, "no questions to grade");
        }
        BigDecimal score = ZERO;
        BigDecimal maxScore = ZERO;
        for (QuestionGrade g : items) {
            score = score.add(g.awardedScore());
            maxScore = maxScore.add(g.maxScore());
        }
        if (maxScore.signum() <= 0) {
            // All-zero maxScore is an invalid exam configuration — never return NaN/Infinity.
            throw new GradingException(GradingErrorCode.GRADING_CONFIGURATION_INVALID, "attempt maxScore is not positive");
        }
        BigDecimal percentage = score.multiply(HUNDRED).divide(maxScore, 2, RoundingMode.HALF_UP);
        return new AttemptGrade(score, maxScore, percentage, List.copyOf(items));
    }

    private static void requirePositiveMaxScore(BigDecimal maxScore) {
        if (maxScore == null || maxScore.signum() <= 0) {
            throw new GradingException(GradingErrorCode.GRADING_CONFIGURATION_INVALID, "question maxScore must be positive");
        }
    }
}
