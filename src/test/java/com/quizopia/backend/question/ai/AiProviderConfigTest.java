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
        assertThat(new AiProviderConfig().mockAiQuizProvider()).isNotNull();
    }
}
