package com.quizopia.backend.question.domain.markdown;

import java.util.Objects;

public record QuizOption(QuizOptionLabel label, String markdown, boolean correct) {
    public QuizOption {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(markdown, "markdown");
    }
}
