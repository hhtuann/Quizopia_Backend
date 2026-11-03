package com.quizopia.backend.question.domain.markdown;

import java.util.List;
import java.util.Objects;

public record QuizContent(List<QuizQuestion> questions) {
    public QuizContent {
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions must not be empty");
        }
    }
}
