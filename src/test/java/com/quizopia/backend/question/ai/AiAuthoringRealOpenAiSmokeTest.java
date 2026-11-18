package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.ai.provider.AiProviderTurn;
import com.quizopia.backend.question.ai.provider.AiProviderResult;
import com.quizopia.backend.question.ai.provider.OpenAiResponsesProvider;
import com.quizopia.backend.question.application.MarkdownQuestionImportService;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REAL OpenAI smoke test (issue #113 acceptance). Runs ONLY when
 * {@code OPENAI_API_KEY} is present in the environment; normal CI/test runs
 * skip it. Never prints the key or any document text — on failure the
 * assertion message contains only the coarse provider message.
 *
 * <p>Run locally with:
 * <pre>OPENAI_API_KEY=... OPENAI_MODEL=gpt-5.4-mini mvnw test -Dtest=AiAuthoringRealOpenAiSmokeTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class AiAuthoringRealOpenAiSmokeTest {

    // Production system prompt (package-private on purpose): the smoke test
    // must validate the REAL prompt's ability to yield conformant output.
    private static final String SYSTEM_PROMPT = AiAuthoringService.SYSTEM_PROMPT;

    @Test
    void realResponsesApiCallReturnsValidQuizMarkdown() {
        AiAuthoringProperties properties = new AiAuthoringProperties(
                true, "openai",
                System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"),
                System.getenv("OPENAI_API_KEY"),
                System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5.4-mini"),
                120, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 0, 1_000, 3_600);
        OpenAiResponsesProvider provider = new OpenAiResponsesProvider(properties);

        AiProviderResult result = provider.complete(List.of(
                new AiProviderTurn("system", SYSTEM_PROMPT),
                new AiProviderTurn("user",
                        "Create exactly 1 SINGLE_CHOICE question with 4 options about the TCP three-way handshake. "
                                + "Current editor Markdown:\n(empty)")));

        assertThat(result.reply()).isNotBlank();
        assertThat(result.markdown()).contains("Question 1 [SINGLE_CHOICE]:");

        QuizMarkdownParseResult parsed = new QuizMarkdownParser().parse(result.markdown());
        assertThat(parsed.isValid())
                .as("real model output must parse: %s", parsed.errors())
                .isTrue();
        assertThat(MarkdownQuestionImportService.bankContractErrors(parsed.content().orElseThrow()))
                .as("real model output must satisfy the bank contract")
                .isEmpty();
    }
}
