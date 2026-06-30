package com.hhtuann.backend.question.dto;

import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * A fully validated and normalized row from the Excel import, ready for
 * persistence in Batch B2.2. Type-irrelevant fields are {@code null}.
 */
public record ValidQuestionRow(
        int rowNumber,
        String questionCode,
        QuestionType questionType,
        String content,
        BigDecimal defaultPoints,
        QuestionDifficulty difficulty,
        String explanation,
        // SINGLE_CHOICE / MULTIPLE_CHOICE only
        Map<String, String> options,
        Set<String> correctAnswers,
        // TRUE_FALSE_MATRIX only
        Map<String, String> statements,
        Map<String, Boolean> statementAnswers,
        // NUMERIC_FILL only
        String expectedAnswer,
        int requiredInputLength,
        String roundingInstruction
) {}
