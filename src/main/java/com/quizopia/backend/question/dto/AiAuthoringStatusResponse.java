package com.quizopia.backend.question.dto;

/**
 * {@code GET /api/question-banks/{bankId}/ai/authoring} — availability of the
 * AI-assisted authoring feature for this bank (Phase 5, issue #113).
 *
 * @param enabled    feature flag state (server config)
 * @param available  flag AND provider key configured — drives the FE UI state
 * @param model      configured model id (never secret)
 */
public record AiAuthoringStatusResponse(boolean enabled, boolean available, String model) {
}
