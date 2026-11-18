package com.quizopia.backend.question.ai.provider;

/**
 * Provider result: the structured {reply, markdown} payload the model was
 * asked to produce (Responses API + JSON Schema Structured Outputs).
 */
public record AiProviderResult(String reply, String markdown) {
}
