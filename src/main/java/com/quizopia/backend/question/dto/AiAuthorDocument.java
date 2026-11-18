package com.quizopia.backend.question.dto;

/**
 * One uploaded-and-extracted document sent back with an authoring request
 * (Phase 5, issue #113). Plain JSON — no multipart re-upload between turns.
 *
 * @param name document name (for prompt labeling only)
 * @param text extracted text previously returned by the extract endpoint
 */
public record AiAuthorDocument(String name, String text) {
}
