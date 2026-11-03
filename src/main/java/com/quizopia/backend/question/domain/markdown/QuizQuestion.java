package com.quizopia.backend.question.domain.markdown;

import com.quizopia.backend.question.domain.model.QuestionType;
import java.util.List;
import java.util.Objects;

public record QuizQuestion(
        int number,
        QuestionType type,
        String stemMarkdown,
        List<QuizOption> options,
        String numericAnswer,
        String explanationMarkdown) {
    public QuizQuestion {
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(stemMarkdown, "stemMarkdown");
        if (stemMarkdown.isBlank()) {
            throw new IllegalArgumentException("stemMarkdown must not be blank");
        }
        options = List.copyOf(Objects.requireNonNull(options, "options"));

        if (type == QuestionType.NUMERIC_FILL) {
            if (!options.isEmpty()) {
                throw new IllegalArgumentException("NUMERIC_FILL must not contain options");
            }
            numericAnswer = QuizNumericAnswer.normalize(numericAnswer);
            if (!QuizNumericAnswer.isValid(numericAnswer)) {
                throw new IllegalArgumentException("NUMERIC_FILL requires a valid four-character numeric answer");
            }
        } else {
            if (numericAnswer != null) {
                throw new IllegalArgumentException("Only NUMERIC_FILL may contain numericAnswer");
            }
            if (options.size() != QuizOptionLabel.values().length) {
                throw new IllegalArgumentException("Choice and matrix questions require exactly A-D");
            }

            QuizOptionLabel[] labels = QuizOptionLabel.values();
            for (int index = 0; index < labels.length; index++) {
                QuizOption option = options.get(index);
                if (option.label() != labels[index]) {
                    throw new IllegalArgumentException("Options must be ordered A-D");
                }
                if (option.markdown().isBlank()) {
                    throw new IllegalArgumentException("Option content must not be blank");
                }
            }

            long correctCount = options.stream().filter(QuizOption::correct).count();
            if (type == QuestionType.SINGLE_CHOICE && correctCount != 1) {
                throw new IllegalArgumentException("SINGLE_CHOICE requires exactly one correct option");
            }
            if (type == QuestionType.MULTIPLE_CHOICE && correctCount < 1) {
                throw new IllegalArgumentException("MULTIPLE_CHOICE requires at least one correct option");
            }
        }
    }
}
