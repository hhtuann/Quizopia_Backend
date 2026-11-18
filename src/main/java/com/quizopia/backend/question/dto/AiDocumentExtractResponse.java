package com.quizopia.backend.question.dto;

/**
 * {@code POST /api/question-banks/{bankId}/ai/documents/extract} response
 * (Phase 5, issue #113). The extracted text lives in the teacher's editing
 * session only — nothing is persisted server-side.
 *
 * @param name      original filename as validated (extension kept)
 * @param text      extracted plain text (UTF-8), possibly truncated
 * @param truncated whether the extracted text was cut at the char cap
 * @param charCount length of the returned (possibly truncated) text
 */
public record AiDocumentExtractResponse(String name, String text, boolean truncated, int charCount) {
}
