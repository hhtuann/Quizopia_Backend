package com.quizopia.backend.question.domain.model;

/**
 * The four MVP question types. The database CHECK constraint on
 * {@code question_versions.question_type} accepts exactly these values
 * (TRUE_FALSE and ESSAY are intentionally not supported).
 */
public enum QuestionType {
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    TRUE_FALSE_MATRIX,
    NUMERIC_FILL
}
