package com.quizopia.backend.question.domain.markdown;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record QuizMarkdownParseResult(Optional<QuizContent> content, List<QuizMarkdownError> errors) {
    public QuizMarkdownParseResult {
        content = Objects.requireNonNull(content, "content");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (errors.isEmpty() == content.isEmpty()) {
            throw new IllegalArgumentException("valid results require content and invalid results require errors");
        }
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    static QuizMarkdownParseResult valid(QuizContent content) {
        return new QuizMarkdownParseResult(Optional.of(content), List.of());
    }

    static QuizMarkdownParseResult invalid(List<QuizMarkdownError> errors) {
        return new QuizMarkdownParseResult(Optional.empty(), errors);
    }
}
