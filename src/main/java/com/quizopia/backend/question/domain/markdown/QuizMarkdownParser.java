package com.quizopia.backend.question.domain.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the accepted Quiz Markdown structural grammar without rendering Markdown content. */
public final class QuizMarkdownParser {
    // Bilingual structural markers (owner decision 2026-11-06: the platform
    // UI is English, so English markers are the authored default; the
    // original Vietnamese markers remain accepted for existing documents.
    // Diagnostics report the SAME codes/positions for both languages.)
    private static final Pattern QUESTION_HEADER = Pattern.compile("^(?:Câu|Question) ([0-9]+) \\[([^\\]]+)]:(.*)$");
    private static final Pattern QUESTION_HEADER_LIKE = Pattern.compile("^(?:Câu|Question) [0-9]+(?: \\[.*)?$");
    private static final Pattern QUESTION_NUMBER_PREFIX = Pattern.compile("^(?:Câu|Question) ([0-9]+).*$");
    private static final Pattern OPTION = Pattern.compile("^(\\*)?([A-D])\\.(.*)$");
    private static final List<String> ANSWER_PREFIXES = List.of("Đáp án:", "Expected answer:");
    private static final List<String> EXPLANATION_PREFIXES = List.of("Lời giải:", "Explanation:");

    private final QuizMarkdownValidator validator;

    public QuizMarkdownParser() {
        this(new QuizMarkdownValidator());
    }

    QuizMarkdownParser(QuizMarkdownValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public QuizMarkdownParseResult parse(String source) {
        String input = source == null ? "" : source;
        List<SourceLine> lines = sourceLines(input);
        List<RawQuestion> questions = new ArrayList<>();
        List<QuizMarkdownError> errors = new ArrayList<>();

        RawQuestion current = null;
        ContentTarget target = ContentTarget.NONE;
        Fence fence = null;
        Integer displayMathLine = null;

        for (SourceLine line : lines) {
            if (displayMathLine != null) {
                appendContent(current, target, line.text(), errors, line.number());
                if (line.text().strip().equals("$$")) {
                    displayMathLine = null;
                }
                continue;
            }
            if (fence != null) {
                appendContent(current, target, line.text(), errors, line.number());
                if (isFenceClosing(line.text(), fence.backtickCount())) {
                    fence = null;
                }
                continue;
            }

            if (line.text().strip().equals("$$")) {
                if (current == null || !target.acceptsMultilineContent()) {
                    errors.add(error(
                            "UNEXPECTED_CONTENT",
                            current == null ? null : current.number,
                            line.number(),
                            1,
                            "Display math is not valid at this structural position"));
                } else {
                    appendContent(current, target, line.text(), errors, line.number());
                    displayMathLine = line.number();
                }
                continue;
            }

            Integer openingFenceLength = openingFenceLength(line.text());
            if (openingFenceLength != null) {
                if (current == null || !target.acceptsMultilineContent()) {
                    errors.add(error(
                            "UNEXPECTED_CONTENT",
                            current == null ? null : current.number,
                            line.number(),
                            1,
                            "A fenced code block is not valid at this structural position"));
                } else {
                    appendContent(current, target, line.text(), errors, line.number());
                    fence = new Fence(openingFenceLength, line.number());
                }
                continue;
            }

            Matcher header = QUESTION_HEADER.matcher(line.text());
            if (header.matches()) {
                Integer questionNumber = parseQuestionNumber(header.group(1), line.number(), errors);
                if (questionNumber == null) {
                    current = null;
                    target = ContentTarget.NONE;
                    continue;
                }
                current = new RawQuestion(questionNumber, header.group(1), header.group(2), line.number(), header.start(1));
                current.stem.add(contentAfterMarker(header.group(3)));
                questions.add(current);
                target = ContentTarget.STEM;
                continue;
            }

            if (looksLikeMalformedQuestionHeader(line.text())) {
                errors.add(error(
                        "MALFORMED_QUESTION_HEADER",
                        malformedQuestionNumber(line.text()),
                        line.number(),
                        1,
                        "Question header must match 'Câu/Question <number> [<TYPE>]:'"));
                current = null;
                target = ContentTarget.NONE;
                continue;
            }

            Matcher option = OPTION.matcher(line.text());
            if (option.matches()) {
                if (current == null) {
                    errors.add(error(
                            "OPTION_OUTSIDE_QUESTION",
                            null,
                            line.number(),
                            1,
                            "Option marker appears before a question"));
                    continue;
                }
                if (current.explanationSeen) {
                    errors.add(error(
                            "OPTION_AFTER_EXPLANATION",
                            current.number,
                            line.number(),
                            1,
                            "Options cannot appear after an explanation marker"));
                }
                RawOption rawOption =
                        new RawOption(QuizOptionLabel.valueOf(option.group(2)), option.group(1) != null, line.number());
                rawOption.content.add(contentAfterMarker(option.group(3)));
                current.options.add(rawOption);
                target = ContentTarget.OPTION;
                continue;
            }

            String answerPrefix = matchedPrefix(line.text(), ANSWER_PREFIXES);
            if (answerPrefix != null) {
                if (current == null) {
                    errors.add(error(
                            "ANSWER_OUTSIDE_QUESTION", null, line.number(), 1,
                            "An answer marker (Đáp án:/Expected answer:) appears before a question"));
                    continue;
                }
                if (current.explanationSeen) {
                    errors.add(error(
                            "ANSWER_AFTER_EXPLANATION",
                            current.number,
                            line.number(),
                            1,
                            "An answer marker (Đáp án:/Expected answer:) cannot appear after an explanation marker"));
                }
                current.answerCount++;
                if (current.answerCount > 1) {
                    errors.add(error(
                            "DUPLICATE_NUMERIC_ANSWER",
                            current.number,
                            line.number(),
                            1,
                            "A question may contain only one answer marker (Đáp án:/Expected answer:)"));
                }
                current.answerLine = line.number();
                current.answerPrefixLength = answerPrefix.length();
                current.answerRaw = line.text().substring(answerPrefix.length());
                target = ContentTarget.AFTER_NUMERIC_ANSWER;
                continue;
            }

            String explanationPrefix = matchedPrefix(line.text(), EXPLANATION_PREFIXES);
            if (explanationPrefix != null) {
                if (current == null) {
                    errors.add(error(
                            "EXPLANATION_OUTSIDE_QUESTION",
                            null,
                            line.number(),
                            1,
                            "An explanation marker (Lời giải:/Explanation:) appears before a question"));
                    continue;
                }
                if (current.explanationSeen) {
                    errors.add(error(
                            "DUPLICATE_EXPLANATION",
                            current.number,
                            line.number(),
                            1,
                            "A question may contain at most one explanation marker (Lời giải:/Explanation:)"));
                    continue;
                }
                current.explanationSeen = true;
                current.explanationLine = line.number();
                current.optionStructureCompleteAtExplanation = hasExactlyOrderedOptions(current.options);
                current.numericAnswerCompleteAtExplanation = current.answerCount == 1
                        && current.answerRaw != null
                        && !current.answerRaw.strip().isEmpty();
                current.explanation.add(contentAfterMarker(line.text().substring(explanationPrefix.length())));
                target = ContentTarget.EXPLANATION;
                continue;
            }


            if (current == null) {
                if (!line.text().isBlank()) {
                    errors.add(error(
                            "CONTENT_OUTSIDE_QUESTION",
                            null,
                            line.number(),
                            1,
                            "Content appears before the first valid question header"));
                }
                continue;
            }

            if (target == ContentTarget.AFTER_NUMERIC_ANSWER) {
                if (!line.text().isBlank()) {
                    errors.add(error(
                            "CONTENT_AFTER_NUMERIC_ANSWER",
                            current.number,
                            line.number(),
                            1,
                            "Only an explanation marker or the next question may follow a NUMERIC_FILL answer"));
                }
                continue;
            }

            appendContent(current, target, line.text(), errors, line.number());
        }

        if (displayMathLine != null) {
            errors.add(error(
                    "UNCLOSED_MATH_BLOCK",
                    current == null ? null : current.number,
                    displayMathLine,
                    1,
                    "Display math block is not closed"));
        }
        if (fence != null) {
            errors.add(error(
                    "UNCLOSED_CODE_FENCE",
                    current == null ? null : current.number,
                    fence.openingLine(),
                    1,
                    "Backtick fenced code block is not closed"));
        }

        return validator.validate(questions, errors);
    }

    private static void appendContent(
            RawQuestion current, ContentTarget target, String text, List<QuizMarkdownError> errors, int line) {
        if (current == null) {
            errors.add(error("CONTENT_OUTSIDE_QUESTION", null, line, 1, "Content appears outside a question"));
            return;
        }
        switch (target) {
            case STEM -> current.stem.add(text);
            case OPTION -> {
                if (current.options.isEmpty()) {
                    errors.add(error(
                            "MALFORMED_STRUCTURE", current.number, line, 1, "Option content has no option marker"));
                } else {
                    current.options.getLast().content.add(text);
                }
            }
            case EXPLANATION -> current.explanation.add(text);
            case NONE, AFTER_NUMERIC_ANSWER ->
                errors.add(error(
                        "UNEXPECTED_CONTENT",
                        current.number,
                        line,
                        1,
                        "Content is not valid at this structural position"));
        }
    }

    private static boolean looksLikeMalformedQuestionHeader(String text) {
        return QUESTION_HEADER_LIKE.matcher(text).matches()
                && !QUESTION_HEADER.matcher(text).matches();
    }

    /** The first marker alias the line starts with, or null. */
    private static String matchedPrefix(String line, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static Integer malformedQuestionNumber(String text) {
        Matcher matcher = QUESTION_NUMBER_PREFIX.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseQuestionNumber(String value, int line, List<QuizMarkdownError> errors) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            errors.add(error(
                    "MALFORMED_QUESTION_HEADER",
                    null,
                    line,
                    5,
                    "Question number is outside the supported integer range"));
            return null;
        }
    }

    private static String contentAfterMarker(String suffix) {
        return suffix.startsWith(" ") ? suffix.substring(1) : suffix;
    }

    private static Integer openingFenceLength(String text) {
        if (!text.startsWith("```")) {
            return null;
        }
        int backticks = 0;
        while (backticks < text.length() && text.charAt(backticks) == '`') {
            backticks++;
        }
        if (backticks < 3) {
            return null;
        }
        String info = text.substring(backticks);
        return info.indexOf('`') >= 0 ? null : backticks;
    }

    private static boolean isFenceClosing(String text, int minimumBackticks) {
        if (text.length() < minimumBackticks || text.charAt(0) != '`') {
            return false;
        }
        int backticks = 0;
        while (backticks < text.length() && text.charAt(backticks) == '`') {
            backticks++;
        }
        return backticks >= minimumBackticks && text.substring(backticks).isBlank();
    }

    private static boolean hasExactlyOrderedOptions(List<RawOption> options) {
        if (options.size() != QuizOptionLabel.values().length) {
            return false;
        }
        QuizOptionLabel[] labels = QuizOptionLabel.values();
        for (int index = 0; index < labels.length; index++) {
            if (options.get(index).label != labels[index]) {
                return false;
            }
        }
        return true;
    }

    private static List<SourceLine> sourceLines(String source) {
        List<SourceLine> lines = new ArrayList<>();
        int start = 0;
        int lineNumber = 1;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '\r' || current == '\n') {
                lines.add(new SourceLine(lineNumber++, source.substring(start, index)));
                if (current == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
        }
        lines.add(new SourceLine(lineNumber, source.substring(start)));
        return lines;
    }

    private static QuizMarkdownError error(String code, Integer question, int line, int column, String message) {
        return new QuizMarkdownError(code, question, line, column, message);
    }

    enum ContentTarget {
        NONE,
        STEM,
        OPTION,
        AFTER_NUMERIC_ANSWER,
        EXPLANATION;

        boolean acceptsMultilineContent() {
            return this == STEM || this == OPTION || this == EXPLANATION;
        }
    }

    record SourceLine(int number, String text) {}

    record Fence(int backtickCount, int openingLine) {}

    static final class RawQuestion {
        final int number;
        final String numberToken;
        final String typeToken;
        final int headerLine;
        /** Length of the matched header marker prefix incl. trailing space ("Câu "/"Question "). */
        final int markerPrefixLength;
        final List<String> stem = new ArrayList<>();
        final List<RawOption> options = new ArrayList<>();
        final List<String> explanation = new ArrayList<>();
        String answerRaw;
        int answerLine;
        /** Length of the matched answer marker ("Đáp án:"/"Expected answer:"). */
        int answerPrefixLength;
        int answerCount;
        boolean explanationSeen;
        int explanationLine;
        boolean optionStructureCompleteAtExplanation;
        boolean numericAnswerCompleteAtExplanation;

        RawQuestion(int number, String numberToken, String typeToken, int headerLine, int markerPrefixLength) {
            this.number = number;
            this.numberToken = numberToken;
            this.typeToken = typeToken;
            this.headerLine = headerLine;
            this.markerPrefixLength = markerPrefixLength;
        }
    }

    static final class RawOption {
        final QuizOptionLabel label;
        final boolean correct;
        final int markerLine;
        final List<String> content = new ArrayList<>();

        RawOption(QuizOptionLabel label, boolean correct, int markerLine) {
            this.label = label;
            this.correct = correct;
            this.markerLine = markerLine;
        }
    }
}
