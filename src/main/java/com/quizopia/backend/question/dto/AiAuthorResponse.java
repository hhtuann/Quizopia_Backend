package com.quizopia.backend.question.dto;

import java.util.List;

/**
 * {@code POST /api/question-banks/{bankId}/ai/author} response
 * (Phase 5, issue #113). The candidate is NEVER persisted — the teacher
 * applies it in the editor and imports through the existing Markdown import
 * endpoint explicitly.
 *
 * @param markdown     complete candidate Quiz Markdown document
 * @param reply        short human-readable assistant reply (chat bubble)
 * @param valid        candidate passes the authoritative parser AND the
 *                     Question Bank contract preflight
 * @param diagnostics  parser/bank-contract diagnostics when {@code valid} is
 *                     false (empty otherwise)
 */
public record AiAuthorResponse(String markdown, String reply, boolean valid, List<AiAuthorDiagnostic> diagnostics) {
}
