package com.quizopia.backend.question;

import com.quizopia.backend.question.application.MarkdownQuestionImportService;
import com.quizopia.backend.question.domain.markdown.QuizContent;
import com.quizopia.backend.question.domain.markdown.QuizOption;
import com.quizopia.backend.question.domain.markdown.QuizOptionLabel;
import com.quizopia.backend.question.domain.markdown.QuizQuestion;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.dto.RowError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the issue #10 bank-contract preflight
 * ({@link MarkdownQuestionImportService#bankContractErrors}): the Markdown
 * grammar deliberately stays donor-pure (MULTIPLE_CHOICE {@code >=1}, numeric
 * {@code -.50}); the import adapter additionally enforces the established
 * Question Bank edit / Exam publish contract before persistence.
 */
class MarkdownBankContractTest {

    private static QuizQuestion choice(int number, QuestionType type, int correctCount) {
        List<QuizOption> options = List.of(
                new QuizOption(QuizOptionLabel.A, "a", correctCount > 0),
                new QuizOption(QuizOptionLabel.B, "b", correctCount > 1),
                new QuizOption(QuizOptionLabel.C, "c", correctCount > 2),
                new QuizOption(QuizOptionLabel.D, "d", correctCount > 3));
        return new QuizQuestion(number, type, "stem", options, null, null);
    }

    private static QuizQuestion numeric(int number, String token) {
        return new QuizQuestion(number, QuestionType.NUMERIC_FILL, "stem", List.of(), token, null);
    }

    @Test
    void multipleChoiceWithOneCorrectViolatesBankContract() {
        List<RowError> errors = MarkdownQuestionImportService.bankContractErrors(
                new QuizContent(List.of(choice(1, QuestionType.MULTIPLE_CHOICE, 1))));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rowNumber()).isEqualTo(1);
        assertThat(errors.get(0).field()).isEqualTo("correctAnswers");
        assertThat(errors.get(0).code()).isEqualTo("MULTIPLE_CHOICE_MIN_TWO_CORRECT");
        assertThat(errors.get(0).message()).contains("at least 2 correct");
    }

    @Test
    void multipleChoiceWithTwoCorrectPasses() {
        assertThat(MarkdownQuestionImportService.bankContractErrors(
                new QuizContent(List.of(choice(1, QuestionType.MULTIPLE_CHOICE, 2)))))
                .isEmpty();
    }

    @Test
    void singleChoiceAndTrueFalseNeverViolate() {
        assertThat(MarkdownQuestionImportService.bankContractErrors(new QuizContent(List.of(
                choice(1, QuestionType.SINGLE_CHOICE, 1),
                choice(2, QuestionType.TRUE_FALSE_MATRIX, 2)))))
                .isEmpty();
    }

    @Test
    void numericLeadingDotViolatesWithActionableMessage() {
        List<RowError> errors = MarkdownQuestionImportService.bankContractErrors(
                new QuizContent(List.of(numeric(3, "-.50"))));

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).rowNumber()).isEqualTo(3);
        assertThat(errors.get(0).field()).isEqualTo("expectedAnswer");
        assertThat(errors.get(0).code()).isEqualTo("NUMERIC_ANSWER_BANK_CONTRACT");
        // Actionable, not a silent rewrite: suggests the canonical spelling.
        assertThat(errors.get(0).message())
                .contains("-.50")
                .contains("-0.5")
                .contains("never rewritten");
    }

    @Test
    void canonicalNumericTokensPass() {
        assertThat(MarkdownQuestionImportService.bankContractErrors(new QuizContent(List.of(
                numeric(1, "2.50"),
                numeric(2, "-3.5"),
                numeric(3, "02.5"),
                numeric(4, "1234")))))
                .isEmpty();
    }

    @Test
    void violationsAreReportedPerQuestion() {
        List<RowError> errors = MarkdownQuestionImportService.bankContractErrors(new QuizContent(List.of(
                choice(1, QuestionType.MULTIPLE_CHOICE, 2),
                choice(2, QuestionType.MULTIPLE_CHOICE, 1),
                numeric(3, "1.25"),
                numeric(4, "-.75"))));

        assertThat(errors)
                .extracting(RowError::rowNumber, RowError::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2, "MULTIPLE_CHOICE_MIN_TWO_CORRECT"),
                        org.assertj.core.groups.Tuple.tuple(4, "NUMERIC_ANSWER_BANK_CONTRACT"));
    }
}
