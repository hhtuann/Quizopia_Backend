package com.quizopia.backend.question.ai.provider;

import java.util.List;

/**
 * Abstraction over the AI provider so the authoring service stays testable
 * and the concrete transport (OpenAI Responses API) can be swapped or faked
 * (Phase 5, issue #113). Implementations must be stateless and thread-safe.
 */
public interface AiQuizProvider {

    /**
     * Sends the conversation and returns the model's structured
     * {@code {reply, markdown}} result.
     *
     * @throws AiProviderException coarse upstream/protocol failure — never a
     *                             business validation error
     */
    AiProviderResult complete(List<AiProviderTurn> turns);
}
