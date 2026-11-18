package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.ai.provider.AiProviderException;
import com.quizopia.backend.question.ai.provider.AiProviderResult;
import com.quizopia.backend.question.ai.provider.AiProviderTurn;
import com.quizopia.backend.question.ai.provider.AiQuizProvider;
import com.quizopia.backend.question.ai.provider.MockAiQuizProvider;
import com.quizopia.backend.question.ai.provider.OpenAiResponsesProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Provider wiring (Phase 5, issue #113 review finding): exactly one
 * {@link AiQuizProvider} bean must exist for ANY configured value of
 * {@code quizopia.ai.authoring.provider}, so a typo degrades only the AI
 * feature (status {@code available=false}, 503 on authoring) instead of
 * failing the whole application context at startup.
 *
 * <p>Bean methods are declared in evaluation order: the real OpenAI adapter
 * (default), the deterministic test mock, then a {@link ConditionalOnMissingBean}
 * fallback that always fails safely.
 */
@Configuration(proxyBeanMethods = false)
public class AiProviderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.ai.authoring", name = "provider",
            havingValue = "openai", matchIfMissing = true)
    public AiQuizProvider openAiResponsesProvider(AiAuthoringProperties properties) {
        return new OpenAiResponsesProvider(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.ai.authoring", name = "provider", havingValue = "mock")
    public AiQuizProvider mockAiQuizProvider() {
        return new MockAiQuizProvider();
    }

    /**
     * Wiring safety net for unknown provider values: every call fails with a
     * coarse provider error (the service's availability gate normally rejects
     * earlier with AI_AUTHORING_DISABLED).
     */
    @Bean
    @ConditionalOnMissingBean(AiQuizProvider.class)
    public AiQuizProvider unavailableAiQuizProvider() {
        return new AiQuizProvider() {
            @Override
            public com.quizopia.backend.question.ai.provider.AiProviderResult complete(List<AiProviderTurn> turns) {
                throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR,
                        "AI provider is not configured (quizopia.ai.authoring.provider)");
            }
        };
    }
}
