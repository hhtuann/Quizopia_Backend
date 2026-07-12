package com.quizopia.backend.question.dto;

import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code PUT /api/questions/{id}}. The question TYPE is
 * immutable (taken from the version) — this request only carries the editable
 * fields: content, difficulty, explanation, options (choice/TF), or
 * expectedAnswer (NUMERIC).
 */
public record UpdateQuestionRequest(
        @NotBlank String content,
        @NotNull QuestionDifficulty difficulty,
        String explanation,
        /** SINGLE/MULTIPLE/TF only (null for NUMERIC). */
        List<OptionPart> options,
        /** NUMERIC_FILL only (null otherwise). */
        String expectedAnswer
) {
    public record OptionPart(String optionKey, String content, Boolean isCorrect) {}
}
