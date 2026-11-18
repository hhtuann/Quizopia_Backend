package com.quizopia.backend.question.dto;

/**
 * One chat-history message sent with an authoring request
 * (Phase 5, issue #113). Roles mirror the provider's user/assistant turns;
 * the assistant side carries the human-readable reply only (never the full
 * candidate document — that is re-sent implicitly via {@code currentMarkdown}).
 *
 * @param role    {@code "user"} or {@code "assistant"}
 * @param content message body
 */
public record AiAuthorMessage(String role, String content) {
}
