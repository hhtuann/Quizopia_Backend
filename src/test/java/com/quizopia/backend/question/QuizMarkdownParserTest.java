package com.quizopia.backend.question;

import com.quizopia.backend.question.domain.markdown.QuizContent;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownError;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser;
import com.quizopia.backend.question.domain.markdown.QuizNumericAnswer;
import com.quizopia.backend.question.domain.markdown.QuizOption;
import com.quizopia.backend.question.domain.markdown.QuizOptionLabel;
import com.quizopia.backend.question.domain.markdown.QuizQuestion;
import com.quizopia.backend.question.domain.model.QuestionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link QuizMarkdownParser} and {@link QuizMarkdownValidator}, ported from
 * the Quizopia 2.0 donor (develop @ b8131fbb, QuizMarkdownParserTest) and extended with the
 * diagnostics the donor suite did not cover. No Spring context is involved; assertions check
 * code + question number + line + column, not just the error count.
 */
class QuizMarkdownParserTest {
    private final QuizMarkdownParser parser = new QuizMarkdownParser();

    // ==================== Happy paths ====================

    @Test
    void parse_fourTypesWithMultilineContent_extractsFullStructure() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Dòng đầu câu hỏi 1
                Dòng thứ hai câu hỏi 1.
                *A. Phương án A dòng 1
                Phương án A dòng 2.
                B. Phương án B
                C. Phương án C
                D. Phương án D
                Lời giải: Giải thích dòng 1
                Giải thích dòng 2.
                Câu 2 [MULTIPLE_CHOICE]: Chọn nhiều đáp án.
                *A. Một
                *B. Hai
                C. Ba
                D. Bốn
                Câu 3 [TRUE_FALSE_MATRIX]: Đánh dấu đúng sai.
                A. Sai A
                B. Sai B
                C. Sai C
                D. Sai D
                Câu 4 [NUMERIC_FILL]: Kết quả là bao nhiêu?
                Đáp án: 2.50
                Lời giải:
                `10 / 4 = 2.5`.
                """;

        QuizContent content = valid(source);

        assertThat(content.questions()).hasSize(4);
        QuizQuestion single = content.questions().get(0);
        assertThat(single.number()).isEqualTo(1);
        assertThat(single.type()).isEqualTo(QuestionType.SINGLE_CHOICE);
        assertThat(single.stemMarkdown()).isEqualTo("Dòng đầu câu hỏi 1\nDòng thứ hai câu hỏi 1.");
        assertThat(single.options().get(0).markdown()).isEqualTo("Phương án A dòng 1\nPhương án A dòng 2.");
        assertThat(single.options().get(0).correct()).isTrue();
        assertThat(single.explanationMarkdown()).isEqualTo("Giải thích dòng 1\nGiải thích dòng 2.");

        QuizQuestion multiple = content.questions().get(1);
        assertThat(multiple.type()).isEqualTo(QuestionType.MULTIPLE_CHOICE);
        assertThat(multiple.options().stream().filter(QuizOption::correct)).hasSize(2);

        QuizQuestion matrix = content.questions().get(2);
        assertThat(matrix.type()).isEqualTo(QuestionType.TRUE_FALSE_MATRIX);
        assertThat(matrix.options().stream().filter(QuizOption::correct)).isEmpty();

        QuizQuestion numeric = content.questions().get(3);
        assertThat(numeric.type()).isEqualTo(QuestionType.NUMERIC_FILL);
        assertThat(numeric.options()).isEmpty();
        assertThat(numeric.numericAnswer()).isEqualTo("2.50");
        assertThat(numeric.explanationMarkdown()).isEqualTo("\n`10 / 4 = 2.5`.\n");
    }

    @Test
    void parse_choiceWithoutExplanation_nullExplanation() {
        QuizQuestion question = valid(singleChoice("*A. correct", "B. b", "C. c", "D. d"))
                .questions()
                .getFirst();

        assertThat(question.explanationMarkdown()).isNull();
    }

    @Test
    void parse_structuralTextInsideFences_treatedAsContent() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Xem đoạn sau:
                ```text
                Câu 99 [MULTIPLE_CHOICE]: fake
                *A. fake
                Đáp án: 1234
                Lời giải: fake
                ```
                Sau fence vẫn là stem.
                *A. Đáp án thật có code:
                ```java
                B. not an option
                System.out.println("Câu 2 [NUMERIC_FILL]:");
                ```
                B. B thật
                C. C thật
                D. D thật
                Lời giải:
                ```text
                Câu 2 [SINGLE_CHOICE]: vẫn chỉ là code
                A. fake
                ```
                Kết thúc lời giải.
                Câu 2 [NUMERIC_FILL]: Giá trị?
                Đáp án: 1234
                """;

        QuizContent content = valid(source);

        assertThat(content.questions()).hasSize(2);
        QuizQuestion first = content.questions().getFirst();
        assertThat(first.stemMarkdown()).contains("Câu 99 [MULTIPLE_CHOICE]: fake");
        assertThat(first.stemMarkdown()).contains("Sau fence vẫn là stem.");
        assertThat(first.options().getFirst().markdown()).contains("B. not an option");
        assertThat(first.explanationMarkdown()).contains("Câu 2 [SINGLE_CHOICE]: vẫn chỉ là code");
        assertThat(first.options().get(1).label()).isEqualTo(QuizOptionLabel.B);

        QuizQuestion second = content.questions().get(1);
        assertThat(second.numericAnswer()).isEqualTo("1234");
    }

    @Test
    void parse_indentedStructuralLookalikes_remainOrdinaryContent() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Stem
                  Câu 99 [NUMERIC_FILL]: not a header
                  A. not an option
                  Lời giải: not an explanation
                *A. real A
                B. real B
                C. real C
                D. real D
                """;

        QuizQuestion question = valid(source).questions().getFirst();

        assertThat(question.stemMarkdown()).contains("  Câu 99 [NUMERIC_FILL]: not a header");
        assertThat(question.stemMarkdown()).contains("  A. not an option");
        assertThat(question.stemMarkdown()).contains("  Lời giải: not an explanation");
        assertThat(question.options()).hasSize(4);
        assertThat(question.explanationMarkdown()).isNull();
    }

    @Test
    void parse_displayMathPreserved_structuralMarkersNotInterpreted() {
        String source =
                """
                Câu 1 [SINGLE_CHOICE]: Compute $x^2$
                $$
                Câu 99 [NUMERIC_FILL]: x^2
                $$
                *A. answer
                $$
                B. x+y
                $$
                B. second
                C. third
                D. fourth
                Lời giải: Reason
                $$
                *A. x^2
                $$
                """;

        QuizContent content = valid(source);
        assertThat(content.questions()).hasSize(1);
        QuizQuestion question = content.questions().getFirst();
        assertThat(question.stemMarkdown()).contains("Câu 99 [NUMERIC_FILL]: x^2");
        assertThat(question.options().getFirst().markdown()).contains("B. x+y");
        assertThat(question.options().get(1).label()).isEqualTo(QuizOptionLabel.B);
        assertThat(question.explanationMarkdown()).contains("*A. x^2");
    }

    @Test
    void parse_crlfInput_trimsOnlyNumericTokenWhitespace() {
        String source = "Câu 1 [NUMERIC_FILL]: Value?\r\nĐáp án: \t-3.5  \r\n";

        QuizQuestion question = valid(source).questions().getFirst();

        assertThat(question.stemMarkdown()).isEqualTo("Value?");
        assertThat(question.numericAnswer()).isEqualTo("-3.5");
    }

    @ParameterizedTest(name = "numeric token {0}")
    @MethodSource("validNumericTokens")
    void parse_validNumericTokens_accepted(String token) {
        QuizQuestion question = valid(numeric(token)).questions().getFirst();

        assertThat(question.numericAnswer()).isEqualTo(token);
    }

    // ==================== Numeric answer grammar ====================

    @ParameterizedTest(name = "reject numeric token {0}")
    @MethodSource("invalidNumericTokens")
    void parse_invalidNumericTokens_rejected(String token) {
        assertError(numeric(token), "NUMERIC_ANSWER_INVALID");
    }

    @Test
    void parse_numericAnswerOnNextLine_rejected() {
        assertError(
                """
                Câu 1 [NUMERIC_FILL]: Value?
                Đáp án:
                2.50
                """,
                "NUMERIC_ANSWER_INVALID");
    }

    @Test
    void parse_invalidNumericAnswer_reportsLineAndColumn() {
        QuizMarkdownError error = assertError("Câu 1 [NUMERIC_FILL]: value?\nĐáp án:   12345\n", "NUMERIC_ANSWER_INVALID");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(2);
        // "Đáp án:" is 7 columns + 3 spaces of padding + 1 → the token starts at column 11.
        assertThat(error.column()).isEqualTo(11);
    }

    @Test
    void constructor_invalidDomainCombinations_rejected() {
        assertThatThrownBy(
                        () -> new QuizQuestion(1, QuestionType.NUMERIC_FILL, "stem", List.of(), "-1.23", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizQuestion(
                        1,
                        QuestionType.NUMERIC_FILL,
                        "stem",
                        List.of(new QuizOption(QuizOptionLabel.A, "not allowed", false)),
                        "1234",
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuizQuestion(1, QuestionType.SINGLE_CHOICE, "stem", List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        QuizQuestion normalized =
                new QuizQuestion(1, QuestionType.NUMERIC_FILL, "stem", List.of(), " \t-.50  ", null);
        assertThat(normalized.numericAnswer()).isEqualTo("-.50");
    }

    @Test
    void quizNumericAnswer_normalize_onlyStripsSurroundingWhitespace() {
        assertThat(QuizNumericAnswer.normalize(null)).isNull();
        assertThat(QuizNumericAnswer.normalize(" 12.3 ")).isEqualTo("12.3");
        assertThat(QuizNumericAnswer.isValid(null)).isFalse();
    }

    // ==================== Structure diagnostics ====================

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidStructures")
    void parse_malformedStructures_reportExpectedCode(String source, String expectedCode) {
        assertError(source, expectedCode);
    }

    @Test
    void parse_nullSource_reportsNoQuestions() {
        QuizMarkdownError error = assertError(null, "NO_QUESTIONS");

        assertThat(error.questionNumber()).isNull();
        assertThat(error.line()).isEqualTo(1);
        assertThat(error.column()).isEqualTo(1);
    }

    @Test
    void parse_unknownType_reportsQuestionLineAndColumn() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [single_choice]: stem
                A. a
                B. b
                C. c
                D. d
                """);

        QuizMarkdownError error = error(result, "UNKNOWN_QUESTION_TYPE");
        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(1);
        // "Câu " (4) + "1" (1) + " [" (2) → the type token starts at column 8.
        assertThat(error.column()).isEqualTo(8);
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void parse_malformedHeader_reportsItsOwnQuestionNumber() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [NUMERIC_FILL]: first
                Đáp án: 1234
                Câu 2 [SINGLE_CHOICE]
                """);

        QuizMarkdownError error = error(result, "MALFORMED_QUESTION_HEADER");
        assertThat(error.questionNumber()).isEqualTo(2);
        assertThat(error.line()).isEqualTo(3);
    }

    @Test
    void parse_numberingDecrease_reportsNotContinuous() {
        QuizMarkdownError error = assertError(
                "Câu 2 [NUMERIC_FILL]: two\nĐáp án: 1234\nCâu 1 [NUMERIC_FILL]: one\nĐáp án: 1234\n",
                "QUESTION_NUMBER_NOT_CONTINUOUS");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(3);
    }

    @Test
    void parse_unclosedFence_reportsOpeningLine() {
        QuizMarkdownParseResult result = parser.parse(
                """
                Câu 1 [SINGLE_CHOICE]: stem
                ```java
                Câu 99 [NUMERIC_FILL]: fake
                """);

        QuizMarkdownError error = error(result, "UNCLOSED_CODE_FENCE");
        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.column()).isEqualTo(1);
    }

    @Test
    void parse_unterminatedDisplayMath_reportsOpeningLine() {
        QuizMarkdownParseResult result = parser.parse("Câu 1 [NUMERIC_FILL]: x\n$$\nCâu 2 [NUMERIC_FILL]: fake");

        assertThat(result.errors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo("UNCLOSED_MATH_BLOCK");
                    assertThat(error.line()).isEqualTo(2);
                });
    }

    // ==================== Marker position diagnostics (donor extension) ====================

    @Test
    void parse_optionBeforeQuestion_reportsOptionOutsideQuestion() {
        QuizMarkdownError error = assertError("A. orphan\n", "OPTION_OUTSIDE_QUESTION");

        assertThat(error.questionNumber()).isNull();
        assertThat(error.line()).isEqualTo(1);
    }

    @Test
    void parse_answerBeforeQuestion_reportsAnswerOutsideQuestion() {
        assertError("Đáp án: 1234\n", "ANSWER_OUTSIDE_QUESTION");
    }

    @Test
    void parse_explanationBeforeQuestion_reportsExplanationOutsideQuestion() {
        assertError("Lời giải: orphan\n", "EXPLANATION_OUTSIDE_QUESTION");
    }

    @Test
    void parse_duplicateNumericAnswerMarker_reportsDuplicate() {
        QuizMarkdownError error =
                assertError("Câu 1 [NUMERIC_FILL]: v?\nĐáp án: 1234\nĐáp án: 5678\n", "DUPLICATE_NUMERIC_ANSWER");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(3);
    }

    @Test
    void parse_contentAfterNumericAnswer_reportsContentAfterAnswer() {
        QuizMarkdownError error =
                assertError("Câu 1 [NUMERIC_FILL]: v?\nĐáp án: 1234\nstray text\n", "CONTENT_AFTER_NUMERIC_ANSWER");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(3);
    }

    @Test
    void parse_optionAfterExplanation_reportsOptionAfterExplanation() {
        QuizMarkdownError error = assertError(
                "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nLời giải: exp\nA. late\n",
                "OPTION_AFTER_EXPLANATION");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(7);
    }

    @Test
    void parse_answerAfterExplanation_reportsAnswerAfterExplanation() {
        assertError(
                "Câu 1 [NUMERIC_FILL]: v?\nLời giải: exp\nĐáp án: 1234\n", "ANSWER_AFTER_EXPLANATION");
    }

    @Test
    void parse_fifthOptionMarker_reportsTooManyOptions() {
        QuizMarkdownError error = assertError(
                "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nA. again\n", "TOO_MANY_OPTIONS");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(6);
    }

    @Test
    void parse_fenceAfterNumericAnswer_reportsUnexpectedContent() {
        QuizMarkdownError error =
                assertError("Câu 1 [NUMERIC_FILL]: v?\nĐáp án: 1234\n```java\n", "UNEXPECTED_CONTENT");

        assertThat(error.questionNumber()).isEqualTo(1);
        assertThat(error.line()).isEqualTo(3);
        assertThat(error.message()).contains("fenced code block");
    }

    @Test
    void parse_displayMathBeforeQuestion_reportsUnexpectedContent() {
        QuizMarkdownError error = assertError("$$\nx\n$$\n", "UNEXPECTED_CONTENT");

        assertThat(error.questionNumber()).isNull();
        assertThat(error.line()).isEqualTo(1);
        assertThat(error.message()).contains("Display math");
    }

    // ==================== Helpers ====================

    private QuizContent valid(String source) {
        QuizMarkdownParseResult result = parser.parse(source);
        assertThat(result.isValid()).as("Expected valid source but got: %s", result.errors()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.content()).isPresent();
        return result.content().orElseThrow();
    }

    private QuizMarkdownError assertError(String source, String code) {
        QuizMarkdownParseResult result = parser.parse(source);
        assertThat(result.isValid()).as("Expected error %s but source was valid", code).isFalse();
        assertThat(result.content()).isEmpty();
        return error(result, code);
    }

    private static QuizMarkdownError error(QuizMarkdownParseResult result, String code) {
        return result.errors().stream()
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing error " + code + ": " + result.errors()));
    }

    private static String singleChoice(String a, String b, String c, String d) {
        return "Câu 1 [SINGLE_CHOICE]: stem\n" + a + "\n" + b + "\n" + c + "\n" + d + "\n";
    }

    private static String numeric(String token) {
        return "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: " + token + "\n";
    }

    private static Stream<String> validNumericTokens() {
        return Stream.of("1234", "0001", "2.50", "0.25", "-3.5", "-0.5", "12.3", "-123", "-.50");
    }

    private static Stream<String> invalidNumericTokens() {
        return Stream.of(
                "123", "12345", "+123", "1,25", ".250", "250.", "1..2", "--12", "1 23", "１２３４", "1e03", "12-3",
                "-1.23");
    }

    private static Stream<Arguments> invalidStructures() {
        return Stream.of(
                Arguments.of("", "NO_QUESTIONS"),
                Arguments.of("plain text", "CONTENT_OUTSIDE_QUESTION"),
                Arguments.of("Câu 0 [NUMERIC_FILL]: value?\nĐáp án: 1234\n", "QUESTION_NUMBER_MUST_START_AT_ONE"),
                Arguments.of("Câu 2 [NUMERIC_FILL]: value?\nĐáp án: 1234\n", "QUESTION_NUMBER_MUST_START_AT_ONE"),
                Arguments.of(
                        "Câu 1 [NUMERIC_FILL]: one\nĐáp án: 1234\nCâu 3 [NUMERIC_FILL]: three\nĐáp án: 1234\n",
                        "QUESTION_NUMBER_GAP"),
                Arguments.of(
                        "Câu 1 [NUMERIC_FILL]: one\nĐáp án: 1234\nCâu 1 [NUMERIC_FILL]: again\nĐáp án: 1234\n",
                        "DUPLICATE_QUESTION_NUMBER"),
                Arguments.of("Câu 1 [UNKNOWN]: stem\nA. a\nB. b\nC. c\nD. d\n", "UNKNOWN_QUESTION_TYPE"),
                Arguments.of("Câu 1 [single_choice]: stem\nA. a\nB. b\nC. c\nD. d\n", "UNKNOWN_QUESTION_TYPE"),
                Arguments.of("Câu 1 [SINGLE_CHOICE]:\n*A. a\nB. b\nC. c\nD. d\n", "EMPTY_STEM"),
                Arguments.of(singleChoice("*A. a", "C. c", "D. d", "D. duplicate"), "MISSING_OPTION"),
                Arguments.of(singleChoice("*A. a", "C. c", "B. b", "D. d"), "MISSING_OPTION"),
                Arguments.of(singleChoice("*A. a", "B. b", "B. duplicate", "D. d"), "DUPLICATE_OPTION"),
                Arguments.of(singleChoice("*A. a", "B.", "C. c", "D. d"), "EMPTY_OPTION"),
                Arguments.of(singleChoice("A. a", "B. b", "C. c", "D. d"), "SINGLE_CHOICE_CORRECT_COUNT"),
                Arguments.of(singleChoice("*A. a", "*B. b", "C. c", "D. d"), "SINGLE_CHOICE_CORRECT_COUNT"),
                Arguments.of(
                        "Câu 1 [MULTIPLE_CHOICE]: stem\nA. a\nB. b\nC. c\nD. d\n", "MULTIPLE_CHOICE_CORRECT_REQUIRED"),
                Arguments.of("Câu 1 [NUMERIC_FILL]: stem\nA. invalid\nĐáp án: 1234\n", "UNEXPECTED_OPTION"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nĐáp án: 1234\n",
                        "UNEXPECTED_NUMERIC_ANSWER"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\nLời giải: too early\n*A. a\nB. b\nC. c\nD. d\n",
                        "EXPLANATION_BEFORE_ANSWER_STRUCTURE"),
                Arguments.of(
                        "Câu 1 [SINGLE_CHOICE]: stem\n*A. a\nB. b\nC. c\nD. d\nLời giải: first\nLời giải: second\n",
                        "DUPLICATE_EXPLANATION"),
                Arguments.of("Câu 1 [SINGLE_CHOICE]: stem\n```java\nint x = 1;\n", "UNCLOSED_CODE_FENCE"));
    }
}
