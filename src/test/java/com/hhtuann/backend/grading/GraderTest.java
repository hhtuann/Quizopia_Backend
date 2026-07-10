package com.hhtuann.backend.grading;

import com.hhtuann.backend.grading.domain.AttemptGrade;
import com.hhtuann.backend.grading.domain.QuestionGrade;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grader unit tests. Pure (no Spring); proves the grading rules: SINGLE/MULTIPLE/NUMERIC are
 * all-or-nothing, TRUE_FALSE_MATRIX awards proportional credit, NUMERIC uses exact String equality
 * (1.00 != 1), order-independent set/matrix comparison, BigDecimal-only arithmetic, and fail-closed on
 * missing key / non-positive max score.
 */
class GraderTest {

    private static final BigDecimal TWO = new BigDecimal("2.00");
    private static final BigDecimal ONE = new BigDecimal("1.00");

    @Nested
    class SingleChoice {
        @Test void exactCorrect() {
            QuestionGrade g = Grader.gradeSingle(ONE, "B", "B");
            assertThat(g.correct()).isTrue(); assertThat(g.answered()).isTrue();
            assertThat(g.awardedScore()).isEqualByComparingTo("1"); assertThat(g.maxScore()).isEqualByComparingTo("1");
        }
        @Test void wrongOption() {
            QuestionGrade g = Grader.gradeSingle(ONE, "B", "A");
            assertThat(g.correct()).isFalse(); assertThat(g.awardedScore()).isEqualByComparingTo("0");
        }
        @Test void unanswered() {
            QuestionGrade g = Grader.gradeSingle(ONE, "B", null);
            assertThat(g.correct()).isFalse(); assertThat(g.answered()).isFalse(); assertThat(g.awardedScore()).isEqualByComparingTo("0");
        }
        @Test void blankSelectedIsUnanswered() {
            QuestionGrade g = Grader.gradeSingle(ONE, "B", "  ");
            assertThat(g.answered()).isFalse();
        }
        @Test void missingCorrectKeyThrows() {
            assertThatThrownBy(() -> Grader.gradeSingle(ONE, null, "B"))
                    .isInstanceOf(GradingException.class).extracting(e -> ((GradingException) e).code())
                    .isEqualTo(GradingErrorCode.GRADING_DATA_INCONSISTENT);
        }
    }

    @Nested
    class MultipleChoice {
        @Test void exactMatch() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("A", "C")).correct()).isTrue();
        }
        @Test void reorderedMatch() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("C", "A")).correct()).isTrue();
        }
        @Test void missingOption() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("A")).correct()).isFalse();
        }
        @Test void extraOption() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("A", "C", "D")).correct()).isFalse();
        }
        @Test void completelyWrong() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("B", "D")).correct()).isFalse();
        }
        @Test void emptyAnswer() {
            QuestionGrade g = Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of());
            assertThat(g.correct()).isFalse(); assertThat(g.answered()).isFalse();
        }
        @Test void duplicateSelectedDedupsThenMatches() {
            // The validator canonicalizes MULTIPLE_CHOICE (sort + dedup) before storage, so the grader
            // receives a Set; duplicates never reach it and are never double-counted.
            Set<String> deduped = new HashSet<>(List.of("A", "A", "C"));
            assertThat(deduped).isEqualTo(Set.of("A", "C"));
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), deduped).correct()).isTrue();
        }
        @Test void invalidOptionOutsideCorrectSet() {
            assertThat(Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("A", "Z")).correct()).isFalse();
        }
        @Test void missingCorrectKeysThrows() {
            assertThatThrownBy(() -> Grader.gradeMultiple(TWO, Set.of(), Set.of("A")))
                    .isInstanceOf(GradingException.class);
        }
    }

    @Nested
    class TrueFalseMatrix {
        private final Map<String, Boolean> key = Map.of("A", true, "B", false, "C", true, "D", false);

        @Test void exactMatch_fullCredit() {
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of("A", true, "B", false, "C", true, "D", false));
            assertThat(g.correct()).isTrue();
            assertThat(g.awardedScore()).isEqualByComparingTo("2.00");
        }
        @Test void reorderedRowsMatch() {
            assertThat(Grader.gradeMatrix(TWO, key, Map.of("D", false, "C", true, "B", false, "A", true)).correct()).isTrue();
        }
        @Test void threeOfFour_proportionalCredit() {
            // 3 of 4 correct → 75% of maxScore; not "fully correct".
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of("A", false, "B", false, "C", true, "D", false));
            assertThat(g.correct()).isFalse();
            assertThat(g.awardedScore()).isEqualByComparingTo("1.50");
        }
        @Test void oneOfFour_proportionalCredit() {
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of("A", true, "B", true, "C", false, "D", true));
            assertThat(g.correct()).isFalse();
            assertThat(g.awardedScore()).isEqualByComparingTo("0.50");
        }
        @Test void missingRowCountsAsNotCorrect() {
            // 3 answered (all right), 1 missing → 3/4 credit.
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of("A", true, "B", false, "C", true));
            assertThat(g.correct()).isFalse();
            assertThat(g.awardedScore()).isEqualByComparingTo("1.50");
        }
        @Test void extraRowIgnoredForCredit() {
            // A key outside the 4 statements is ignored; the 4 correct → full credit.
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of("A", true, "B", false, "C", true, "D", false, "E", true));
            assertThat(g.correct()).isTrue();
            assertThat(g.awardedScore()).isEqualByComparingTo("2.00");
        }
        @Test void unanswered() {
            QuestionGrade g = Grader.gradeMatrix(TWO, key, Map.of());
            assertThat(g.correct()).isFalse(); assertThat(g.answered()).isFalse();
            assertThat(g.awardedScore()).isEqualByComparingTo("0");
        }
        @Test void missingKeyThrows() {
            assertThatThrownBy(() -> Grader.gradeMatrix(TWO, Map.of(), Map.of("A", true))).isInstanceOf(GradingException.class);
        }
    }

    @Nested
    class NumericFill {
        @Test void exactStringMatch() {
            assertThat(Grader.gradeNumeric(TWO, "1.00", "1.00").correct()).isTrue();
        }
        @Test void differentRepresentationIsWrong() {
            // Exact string match: "1" != "1.00" (no numeric normalization).
            assertThat(Grader.gradeNumeric(TWO, "1.00", "1").correct()).isFalse();
        }
        @Test void negative() {
            assertThat(Grader.gradeNumeric(TWO, "-1.25", "-1.25").correct()).isTrue();
        }
        @Test void zero() {
            assertThat(Grader.gradeNumeric(TWO, "0.00", "0.00").correct()).isTrue();
        }
        @Test void decimal() {
            assertThat(Grader.gradeNumeric(TWO, "2.50", "2.50").correct()).isTrue();
        }
        @Test void wrongValue() {
            assertThat(Grader.gradeNumeric(TWO, "2.50", "3.00").correct()).isFalse();
        }
        @Test void trailingSpaceIsWrong() {
            assertThat(Grader.gradeNumeric(TWO, "1.00", "1.0 ").correct()).isFalse();
        }
        @Test void unanswered() {
            QuestionGrade g = Grader.gradeNumeric(TWO, "2.50", null);
            assertThat(g.correct()).isFalse(); assertThat(g.answered()).isFalse();
        }
        @Test void missingExpectedThrows() {
            assertThatThrownBy(() -> Grader.gradeNumeric(TWO, null, "1"))
                    .isInstanceOf(GradingException.class);
        }
    }

    @Nested
    class Aggregation {
        @Test void mixedFourTypesAllCorrect() {
            AttemptGrade a = Grader.aggregate(List.of(
                    Grader.gradeSingle(ONE, "B", "B"),
                    Grader.gradeMultiple(TWO, Set.of("A", "C"), Set.of("A", "C")),
                    Grader.gradeMatrix(ONE, Map.of("A", true), Map.of("A", true)),
                    Grader.gradeNumeric(ONE, "1.00", "1.00")));
            assertThat(a.maxScore()).isEqualByComparingTo("5");
            assertThat(a.score()).isEqualByComparingTo("5");
            assertThat(a.percentage()).isEqualByComparingTo("100.00");
        }
        @Test void allWrong() {
            AttemptGrade a = Grader.aggregate(List.of(
                    Grader.gradeSingle(ONE, "B", "A"),
                    Grader.gradeNumeric(ONE, "1.00", "2.00")));
            assertThat(a.score()).isEqualByComparingTo("0");
            assertThat(a.percentage()).isEqualByComparingTo("0.00");
        }
        @Test void partialAndPercentageRounding() {
            // 1 of 3 → 33.33% (HALF_UP).
            AttemptGrade a = Grader.aggregate(List.of(
                    Grader.gradeSingle(ONE, "B", "B"),
                    Grader.gradeSingle(ONE, "B", "A"),
                    Grader.gradeSingle(ONE, "B", null)));
            assertThat(a.score()).isEqualByComparingTo("1");
            assertThat(a.maxScore()).isEqualByComparingTo("3");
            assertThat(a.percentage()).isEqualByComparingTo("33.33");
        }
        @Test void emptyThrows() {
            assertThatThrownBy(() -> Grader.aggregate(List.of()))
                    .isInstanceOf(GradingException.class)
                    .extracting(e -> ((GradingException) e).code()).isEqualTo(GradingErrorCode.GRADING_DATA_INCONSISTENT);
        }
        @Test void nonPositiveMaxScoreThrows() {
            assertThatThrownBy(() -> Grader.gradeSingle(BigDecimal.ZERO, "B", "B"))
                    .isInstanceOf(GradingException.class)
                    .extracting(e -> ((GradingException) e).code()).isEqualTo(GradingErrorCode.GRADING_CONFIGURATION_INVALID);
        }
        @Test void itemsAreCopiedAndStable() {
            QuestionGrade q = Grader.gradeSingle(ONE, "B", "B");
            AttemptGrade a = Grader.aggregate(List.of(q));
            assertThat(a.items()).containsExactly(q);
        }
    }

    @Test
    void noDoubleOrFloatInGradingPath() {
        // Sanity: the awarded score for a correct answer equals the configured maxScore exactly (BigDecimal),
        // and percentage is a BigDecimal — the engine never produces a double-typed result.
        AttemptGrade a = Grader.aggregate(List.of(Grader.gradeSingle(ONE, "B", "B")));
        assertThat(a.score().getClass()).isEqualTo(BigDecimal.class);
        assertThat(a.percentage().getClass()).isEqualTo(BigDecimal.class);
    }
}
