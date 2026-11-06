package com.quizopia.backend.question.domain.markdown;

import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser.RawOption;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser.RawQuestion;
import com.quizopia.backend.question.domain.model.QuestionType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class QuizMarkdownValidator {
    /**
     * The four types accepted by the Quiz Markdown grammar. Deliberately an explicit frozen
     * set rather than {@code QuestionType.values()}: the grammar is versioned by the donor
     * specification and must not silently gain types if the shared enum evolves.
     */
    private static final Set<QuestionType> GRAMMAR_TYPES = EnumSet.of(
            QuestionType.SINGLE_CHOICE,
            QuestionType.MULTIPLE_CHOICE,
            QuestionType.TRUE_FALSE_MATRIX,
            QuestionType.NUMERIC_FILL);

    private static final List<QuizOptionLabel> REQUIRED_OPTION_ORDER =
            List.of(QuizOptionLabel.A, QuizOptionLabel.B, QuizOptionLabel.C, QuizOptionLabel.D);

    QuizMarkdownParseResult validate(List<RawQuestion> questions, List<QuizMarkdownError> parserErrors) {
        List<QuizMarkdownError> errors = new ArrayList<>(parserErrors);
        List<QuizQuestion> validated = new ArrayList<>();

        if (questions.isEmpty()) {
            errors.add(error("NO_QUESTIONS", null, 1, 1, "Quiz Markdown must contain at least one question"));
            return QuizMarkdownParseResult.invalid(errors);
        }

        validateNumbering(questions, errors);
        for (RawQuestion question : questions) {
            Optional<QuestionType> maybeType = fromToken(question.typeToken);
            if (maybeType.isEmpty()) {
                errors.add(error(
                        "UNKNOWN_QUESTION_TYPE",
                        question.number,
                        question.headerLine,
                        typeColumn(question),
                        "Unknown or incorrectly cased question type: " + question.typeToken));
                continue;
            }

            QuestionType type = maybeType.orElseThrow();
            String stem = markdown(question.stem);
            if (stem.isBlank()) {
                errors.add(error(
                        "EMPTY_STEM", question.number, question.headerLine, 1, "Question stem must not be blank"));
            }

            String explanation = question.explanationSeen ? markdown(question.explanation) : null;
            int beforeQuestionErrors = errors.size();
            List<QuizOption> options = List.of();
            String numericAnswer = null;

            if (type == QuestionType.NUMERIC_FILL) {
                validateNumeric(question, errors);
                if (question.answerRaw != null) {
                    numericAnswer = question.answerRaw.strip();
                }
            } else {
                options = validateOptions(question, type, errors);
                if (question.answerCount > 0) {
                    errors.add(error(
                            "UNEXPECTED_NUMERIC_ANSWER",
                            question.number,
                            question.answerLine,
                            1,
                            "An answer marker (Đáp án:/Expected answer:) is valid only for NUMERIC_FILL"));
                }
            }

            validateExplanationPosition(question, type, errors);
            if (errors.size() == beforeQuestionErrors && question.number > 0 && !stem.isBlank()) {
                validated.add(new QuizQuestion(question.number, type, stem, options, numericAnswer, explanation));
            }
        }

        return errors.isEmpty()
                ? QuizMarkdownParseResult.valid(new QuizContent(validated))
                : QuizMarkdownParseResult.invalid(errors);
    }

    /** Case-sensitive exact-name match, restricted to the four grammar types. */
    private static Optional<QuestionType> fromToken(String token) {
        return GRAMMAR_TYPES.stream()
                .filter(value -> value.name().equals(token))
                .findFirst();
    }

    private static void validateNumbering(List<RawQuestion> questions, List<QuizMarkdownError> errors) {
        RawQuestion first = questions.getFirst();
        if (first.number != 1) {
            errors.add(error(
                    "QUESTION_NUMBER_MUST_START_AT_ONE",
                    first.number,
                    first.headerLine,
                    5,
                    "Question numbering must begin at 1"));
        }

        Set<Integer> seen = new java.util.HashSet<>();
        for (int index = 0; index < questions.size(); index++) {
            RawQuestion question = questions.get(index);
            int expected = index + 1;
            if (!seen.add(question.number)) {
                errors.add(error(
                        "DUPLICATE_QUESTION_NUMBER",
                        question.number,
                        question.headerLine,
                        5,
                        "Question number " + question.number + " is duplicated"));
            } else if (question.number != expected && !(index == 0 && question.number != 1)) {
                errors.add(error(
                        question.number > expected ? "QUESTION_NUMBER_GAP" : "QUESTION_NUMBER_NOT_CONTINUOUS",
                        question.number,
                        question.headerLine,
                        5,
                        "Expected question number " + expected + " but found " + question.number));
            }
        }
    }

    private static List<QuizOption> validateOptions(
            RawQuestion question, QuestionType type, List<QuizMarkdownError> errors) {
        List<QuizOption> options = new ArrayList<>();
        Set<QuizOptionLabel> seen = EnumSet.noneOf(QuizOptionLabel.class);

        for (int index = 0; index < question.options.size(); index++) {
            RawOption option = question.options.get(index);
            if (!seen.add(option.label)) {
                errors.add(error(
                        "DUPLICATE_OPTION",
                        question.number,
                        option.markerLine,
                        1,
                        "Option " + option.label + " is duplicated"));
            }
            if (index >= REQUIRED_OPTION_ORDER.size()) {
                errors.add(error(
                        "TOO_MANY_OPTIONS",
                        question.number,
                        option.markerLine,
                        1,
                        "Choice and matrix questions require exactly A-D"));
            } else if (option.label != REQUIRED_OPTION_ORDER.get(index)) {
                QuizOptionLabel expected = REQUIRED_OPTION_ORDER.get(index);
                errors.add(error(
                        option.label.ordinal() > expected.ordinal() ? "MISSING_OPTION" : "OPTION_OUT_OF_ORDER",
                        question.number,
                        option.markerLine,
                        1,
                        "Expected option " + expected + " but found " + option.label));
            }

            String content = markdown(option.content);
            if (content.isBlank()) {
                errors.add(error(
                        "EMPTY_OPTION",
                        question.number,
                        option.markerLine,
                        1,
                        "Option " + option.label + " must not be blank"));
            }
            options.add(new QuizOption(option.label, content, option.correct));
        }

        if (question.options.size() < REQUIRED_OPTION_ORDER.size()) {
            for (QuizOptionLabel required : REQUIRED_OPTION_ORDER) {
                if (!seen.contains(required)) {
                    errors.add(error(
                            "MISSING_OPTION",
                            question.number,
                            question.headerLine,
                            1,
                            "Missing required option " + required));
                }
            }
        }

        long correctCount =
                question.options.stream().filter(option -> option.correct).count();
        if (type == QuestionType.SINGLE_CHOICE && correctCount != 1) {
            errors.add(error(
                    "SINGLE_CHOICE_CORRECT_COUNT",
                    question.number,
                    question.headerLine,
                    1,
                    "SINGLE_CHOICE requires exactly one correct option"));
        }
        if (type == QuestionType.MULTIPLE_CHOICE && correctCount < 1) {
            errors.add(error(
                    "MULTIPLE_CHOICE_CORRECT_REQUIRED",
                    question.number,
                    question.headerLine,
                    1,
                    "MULTIPLE_CHOICE requires at least one correct option"));
        }
        return List.copyOf(options);
    }

    private static void validateNumeric(RawQuestion question, List<QuizMarkdownError> errors) {
        if (!question.options.isEmpty()) {
            RawOption first = question.options.getFirst();
            errors.add(error(
                    "UNEXPECTED_OPTION",
                    question.number,
                    first.markerLine,
                    1,
                    "NUMERIC_FILL does not use A-D options"));
        }
        if (question.answerCount == 0 || question.answerRaw == null) {
            errors.add(error(
                    "NUMERIC_ANSWER_REQUIRED",
                    question.number,
                    question.headerLine,
                    1,
                    "NUMERIC_FILL requires an answer marker (Đáp án:/Expected answer:) <token> on the same line"));
            return;
        }
        String token = question.answerRaw.strip();
        if (!QuizNumericAnswer.isValid(token)) {
            errors.add(error(
                    "NUMERIC_ANSWER_INVALID",
                    question.number,
                    question.answerLine,
                    answerColumn(question.answerRaw, question.answerPrefixLength),
                    "Numeric answer must be exactly four ASCII characters and follow the accepted '-' and '.' rules"));
        }
    }

    private static void validateExplanationPosition(
            RawQuestion question, QuestionType type, List<QuizMarkdownError> errors) {
        if (!question.explanationSeen) {
            return;
        }
        boolean answerComplete = type == QuestionType.NUMERIC_FILL
                ? question.numericAnswerCompleteAtExplanation
                : question.optionStructureCompleteAtExplanation;
        if (!answerComplete) {
            errors.add(error(
                    "EXPLANATION_BEFORE_ANSWER_STRUCTURE",
                    question.number,
                    question.explanationLine,
                    1,
                    "An explanation marker may appear only after the complete answer structure"));
        }
    }

    private static int typeColumn(RawQuestion question) {
        // Raw digit token length: "Câu/Question 007" must report the column
        // where the type token actually starts — computed from the ACTUAL
        // matched marker prefix, not the normalized integer's length.
        return question.markerPrefixLength + question.numberToken.length() + 3;
    }

    private static int answerColumn(String rawSuffix, int answerPrefixLength) {
        int leadingWhitespace = 0;
        while (leadingWhitespace < rawSuffix.length() && Character.isWhitespace(rawSuffix.charAt(leadingWhitespace))) {
            leadingWhitespace++;
        }
        return answerPrefixLength + leadingWhitespace + 1;
    }

    private static String markdown(List<String> lines) {
        return String.join("\n", lines);
    }

    private static QuizMarkdownError error(String code, Integer question, int line, int column, String message) {
        return new QuizMarkdownError(code, question, line, column, message);
    }
}
