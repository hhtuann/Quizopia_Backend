package com.quizopia.backend.question.ai.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic in-process provider for automated tests ONLY
 * ({@code quizopia.ai.authoring.provider=mock}). Never used in production —
 * the live demo must use the real OpenAI provider (issue #112: no fake
 * success in the live demo).
 *
 * <p>Tests steer the behavior through the static knobs and must reset them
 * afterwards ({@link #reset()}).
 */
public class MockAiQuizProvider implements AiQuizProvider {

    /** Valid one-question document returned by default. */
    public static final String DEFAULT_MARKDOWN = """
            Question 1 [SINGLE_CHOICE]: What is 2 + 2?
            *A. 4
            B. 3
            C. 5
            D. 6
            """;

    /** Candidate markdown the next call returns (defaults to {@link #DEFAULT_MARKDOWN}). */
    public static final AtomicReference<String> NEXT_MARKDOWN = new AtomicReference<>(DEFAULT_MARKDOWN);

    /** Reply text the next call returns. */
    public static final AtomicReference<String> NEXT_REPLY = new AtomicReference<>("Generated 1 question.");

    /** When non-null, thrown by the next call instead of returning a result. */
    public static final AtomicReference<RuntimeException> NEXT_FAILURE = new AtomicReference<>();

    /** Number of {@link #complete} invocations since the last reset. */
    public static final AtomicInteger CALLS = new AtomicInteger();

    @Override
    public AiProviderResult complete(List<AiProviderTurn> turns) {
        CALLS.incrementAndGet();
        RuntimeException failure = NEXT_FAILURE.get();
        if (failure != null) {
            throw failure;
        }
        return new AiProviderResult(NEXT_REPLY.get(), NEXT_MARKDOWN.get());
    }

    /** Restores defaults; call in {@code @AfterEach}/{@code @BeforeEach}. */
    public static void reset() {
        NEXT_MARKDOWN.set(DEFAULT_MARKDOWN);
        NEXT_REPLY.set("Generated 1 question.");
        NEXT_FAILURE.set(null);
        CALLS.set(0);
    }
}
