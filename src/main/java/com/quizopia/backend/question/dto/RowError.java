package com.quizopia.backend.question.dto;

/**
 * A single row-level validation error from a question import (Excel workbook
 * or Quiz Markdown document).
 *
 * <p>Row errors do NOT abort the whole import; they are collected and returned
 * as part of {@link ImportResult#errors()} (Excel) or the all-or-nothing
 * Markdown rejection body.
 *
 * <p>{@code line}/{@code column} carry the AUTHORITATIVE parser's source
 * position for Quiz Markdown diagnostics (1-based, exactly as
 * {@code QuizMarkdownError} reports them) so the Teacher UI can point at the
 * offending source location. They are {@code null} for Excel row errors
 * (row-number based) and for Markdown bank-contract findings that are keyed
 * by question number only.
 */
public record RowError(
        int rowNumber,
        String questionCode,
        String field,
        String code,
        String message,
        Integer line,
        Integer column
) {
    /** Excel/contract shape: no Markdown source coordinates. */
    public RowError(int rowNumber, String questionCode, String field, String code, String message) {
        this(rowNumber, questionCode, field, code, message, null, null);
    }
}
