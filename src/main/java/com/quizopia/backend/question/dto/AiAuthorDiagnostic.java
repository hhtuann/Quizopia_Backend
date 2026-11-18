package com.quizopia.backend.question.dto;

/**
 * One validation diagnostic for an AI-authored Markdown candidate
 * (Phase 5, issue #113). Mirrors the authoritative
 * {@code QuizMarkdownError} / {@code RowError} shapes.
 *
 * @param code           stable diagnostic code
 * @param questionNumber 1-based question number, or null for document-level
 * @param line           1-based source line (0 when unknown)
 * @param column         1-based source column (0 when unknown)
 * @param message        human-readable diagnostic
 */
public record AiAuthorDiagnostic(String code, Integer questionNumber, int line, int column, String message) {
}
