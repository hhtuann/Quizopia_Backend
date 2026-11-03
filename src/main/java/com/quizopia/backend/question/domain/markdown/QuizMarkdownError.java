package com.quizopia.backend.question.domain.markdown;

import java.util.Objects;

public record QuizMarkdownError(String code, Integer questionNumber, int line, int column, String message) {
    public QuizMarkdownError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive");
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be positive");
        }
    }
}
