package com.quizopia.backend.question.ai.provider;

/**
 * One conversation turn sent to the AI provider. Roles are the provider's
 * {@code system} / {@code user} / {@code assistant}.
 */
public record AiProviderTurn(String role, String content) {
}
