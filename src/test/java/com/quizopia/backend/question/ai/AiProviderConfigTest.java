package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.ai.provider.AiProviderException;
import com.quizopia.backend.question.ai.provider.AiProviderTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AiProviderConfig} (review finding: an unknown
 * {@code quizopia.ai.authoring.provider} value must degrade the AI feature,
 * not the whole application context). The conditional wiring itself is
 * exercised by the integration tests (mock profile) — here the fallback's
 * behavior is pinned directly.
 */
class AiProviderConfigTest {

    @Test
    void fallbackProviderFailsSafely() {
        var fallback = new AiProviderConfig().unavailableAiQuizProvider();

        assertThatThrownBy(() -> fallback.complete(List.of(new AiProviderTurn("system", "s"))))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.UPSTREAM_ERROR))
                .hasMessageContaining("not configured");
    }

    @Test
    void configExposesOpenAiAndMockFactories() {
        var properties = new AiAuthoringProperties(
                true, "openai", "https://api.example.test/v1", "k", "m",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 1_000, 3_600);
        assertThat(new AiProviderConfig().openAiResponsesProvider(properties)).isNotNull();
    }

    /**
     * Gate review P2: {@code provider=mock} must be usable ONLY in the test
     * profile. Outside it the context must fail closed with a clear message
     * instead of silently serving a fake provider (no fake live-demo success).
     */
    @Test
    void mockProviderAllowedOnlyInTestProfile() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AiProviderConfig.class)
                .withPropertyValues("quizopia.ai.authoring.provider=mock")
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasSingleBean(
                        com.quizopia.backend.question.ai.provider.MockAiQuizProvider.class));

        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AiProviderConfig.class)
                .withPropertyValues("quizopia.ai.authoring.provider=mock")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("only allowed with the 'test' profile");
                });
    }

    /** Unknown provider values still degrade gracefully via the fallback. */
    @Test
    void unknownProviderFallsBackInsteadOfFailingStartup() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AiProviderConfig.class)
                .withPropertyValues("quizopia.ai.authoring.provider=openAi-typo")
                .run(context -> assertThat(context).hasSingleBean(AiProviderConfig.class)
                        .doesNotHaveBean(com.quizopia.backend.question.ai.provider.MockAiQuizProvider.class));
    }
}
