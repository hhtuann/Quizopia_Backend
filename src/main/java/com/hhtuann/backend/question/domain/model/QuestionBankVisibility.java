package com.hhtuann.backend.question.domain.model;

/**
 * Visibility of a {@link QuestionBank}. MVP banks are {@link #PRIVATE}
 * (owner-only).
 */
public enum QuestionBankVisibility {
    PRIVATE,
    SHARED,
    PUBLIC
}
