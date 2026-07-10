package com.hhtuann.backend.question.dto;

import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionType;

import java.util.Map;
import java.util.Set;

/**
 * A fully validated and normalized row from the Excel import, ready for
 * persistence. Type-irrelevant fields are {@code null}. The question code is
 * auto-generated at persist time and {@code default_points} is always 1.
 */
public record ValidQuestionRow(
        int rowNumber,
        QuestionType questionType,
        String content,
        QuestionDifficulty difficulty,
        String explanation,
        // SINGLE_CHOICE / MULTIPLE_CHOICE only
        Map<String, String> options,
        Set<String> correctAnswers,
        // TRUE_FALSE_MATRIX only
        Map<String, String> statements,
        Map<String, Boolean> statementAnswers,
        // NUMERIC_FILL only
        String expectedAnswer
) {}
