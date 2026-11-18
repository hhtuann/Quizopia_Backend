package com.quizopia.backend.question.dto;

import java.util.List;

/**
 * {@code POST /api/question-banks/{bankId}/ai/author} request body
 * (Phase 5, issue #113). All sizes are validated server-side before any
 * provider call; authorization runs before request validation.
 *
 * @param instruction     the teacher's natural-language instruction (required)
 * @param currentMarkdown current editor source (may be empty/null)
 * @param documents       extracted document texts grounding the generation
 * @param history         prior chat turns (bounded)
 */
public record AiAuthorRequest(
        String instruction,
        String currentMarkdown,
        List<AiAuthorDocument> documents,
        List<AiAuthorMessage> history) {
}
