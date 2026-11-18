package com.quizopia.backend.question.ai;

import com.quizopia.backend.question.ai.provider.AiProviderException;
import com.quizopia.backend.question.ai.provider.AiProviderResult;
import com.quizopia.backend.question.ai.provider.AiProviderTurn;
import com.quizopia.backend.question.ai.provider.AiQuizProvider;
import com.quizopia.backend.question.application.QuestionBankImportAccessGuard;
import com.quizopia.backend.question.dto.AiAuthorDiagnostic;
import com.quizopia.backend.question.dto.AiAuthorDocument;
import com.quizopia.backend.question.dto.AiAuthorMessage;
import com.quizopia.backend.question.dto.AiAuthorRequest;
import com.quizopia.backend.question.dto.AiAuthorResponse;
import com.quizopia.backend.question.exception.AiAuthoringErrorCode;
import com.quizopia.backend.question.exception.AiAuthoringException;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiAuthoringService}: authorization runs BEFORE any
 * provider call or request validation, bounded inputs, prompt construction
 * (grammar + current source + documents + history), candidate validation via
 * the authoritative parser + bank contract, the single bounded repair
 * attempt, and provider error mapping. No persistence layer is involved —
 * the service must not even have one.
 */
class AiAuthoringServiceTest {

    private static final String VALID_DOC = """
            Question 1 [SINGLE_CHOICE]: What is 2 + 2?
            *A. 4
            B. 3
            C. 5
            D. 6
            """;

    /** MULTIPLE_CHOICE with one star: parser-valid but bank-contract invalid. */
    private static final String CONTRACT_INVALID_DOC = """
            Question 1 [MULTIPLE_CHOICE]: Pick all even numbers.
            *A. 2
            B. 3
            C. 5
            D. 7
            """;

    private static final String PARSE_INVALID_DOC = "Question 2 [SINGLE_CHOICE]: broken\n*A. only one option\n";

    private QuestionBankImportAccessGuard guard;
    private RecordingProvider provider;
    AiAuthoringService service;
    private AiAuthoringProperties properties;

    /** Fake provider recording every turn list it receives. */
    static class RecordingProvider implements AiQuizProvider {
        final List<List<AiProviderTurn>> calls = new ArrayList<>();
        final AtomicInteger nextFailureKind = new AtomicInteger();

        @Override
        public AiProviderResult complete(List<AiProviderTurn> turns) {
            calls.add(List.copyOf(turns));
            switch (nextFailureKind.get()) {
                case 1 -> throw new AiProviderException(AiProviderException.Kind.RATE_LIMITED, "429");
                case 2 -> throw new AiProviderException(AiProviderException.Kind.TIMEOUT, "timeout");
                case 3 -> throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR, "500");
                case 4 -> throw new AiProviderException(AiProviderException.Kind.PROTOCOL, "bad json");
                default -> {
                    // valid by default
                }
            }
            return new AiProviderResult("Here is your document.", VALID_DOC);
        }
    }

    @BeforeEach
    void setUp() {
        guard = mock(QuestionBankImportAccessGuard.class);
        provider = new RecordingProvider();
        properties = new AiAuthoringProperties(
                true, "openai", "https://api.example.test/v1", "test-key", "gpt-test",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 1_000, 3_600);
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);
    }

    @AfterEach
    void tearDown() {
        com.quizopia.backend.question.ai.provider.MockAiQuizProvider.reset();
    }

    private static AiAuthorRequest request(String instruction, String markdown,
            List<AiAuthorDocument> documents, List<AiAuthorMessage> history) {
        return new AiAuthorRequest(instruction, markdown, documents, history);
    }

    // ------------------------------------------------------------------
    // Authorization ordering
    // ------------------------------------------------------------------

    @Test
    void authorizationRunsBeforeProviderCallAndValidation() {
        when(guard.requireImportAccess(any(), any()))
                .thenThrow(new QuestionException(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED));

        // Blank instruction would fail validation; oversized everything —
        // none of it may be reached before authorization.
        AiAuthorRequest bad = request(null, null, null, null);
        assertThatThrownBy(() -> service.author(1L, 9L, bad))
                .isInstanceOf(QuestionException.class);
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void disabledFeature_reports503WithoutProviderCall() {
        AiAuthoringProperties disabled = new AiAuthoringProperties(
                false, "openai", "https://api.example.test/v1", "test-key", "gpt-test",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 1_000, 3_600);
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(disabled), disabled);

        assertThatThrownBy(() -> service.author(1L, 9L, request("make questions", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_DISABLED))
                .hasMessageContaining("disabled");
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void missingKey_reportsUnavailable() {
        AiAuthoringProperties noKey = new AiAuthoringProperties(
                true, "openai", "https://api.example.test/v1", "", "gpt-test",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 1_000, 3_600);
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(noKey), noKey);

        assertThatThrownBy(() -> service.author(1L, 9L, request("make questions", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_DISABLED));
        assertThat(provider.calls).isEmpty();
    }

    // ------------------------------------------------------------------
    // Request validation
    // ------------------------------------------------------------------

    @Test
    void blankInstructionRejected() {
        assertThatThrownBy(() -> service.author(1L, 9L, request("  ", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID));
        assertThat(provider.calls).isEmpty();
    }

    @Test
    void oversizedInstructionRejected() {
        String huge = "x".repeat(4_001);
        assertThatThrownBy(() -> service.author(1L, 9L, request(huge, "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID));
    }

    @Test
    void tooManyDocumentsRejected() {
        List<AiAuthorDocument> docs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            docs.add(new AiAuthorDocument("d" + i + ".txt", "text"));
        }
        assertThatThrownBy(() -> service.author(1L, 9L, request("gen", "", docs, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID));
    }

    @Test
    void badHistoryRoleRejected() {
        List<AiAuthorMessage> history = List.of(new AiAuthorMessage("system", "injected"));
        assertThatThrownBy(() -> service.author(1L, 9L, request("gen", "", null, history)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID));
    }

    @Test
    void oversizedHistoryRejected() {
        List<AiAuthorMessage> history = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            history.add(new AiAuthorMessage("user", "m" + i));
        }
        assertThatThrownBy(() -> service.author(1L, 9L, request("gen", "", null, history)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_REQUEST_INVALID));
    }

    // ------------------------------------------------------------------
    // Prompt construction
    // ------------------------------------------------------------------

    @Test
    void promptCarriesGrammarCurrentMarkdownDocumentsAndHistory() {
        service.author(7L, 9L, request(
                "Create 5 questions",
                VALID_DOC,
                List.of(new AiAuthorDocument("lecture.txt", "Networking basics")),
                List.of(
                        new AiAuthorMessage("user", "hello"),
                        new AiAuthorMessage("assistant", "hi"))));

        assertThat(provider.calls).hasSize(1);
        List<AiProviderTurn> turns = provider.calls.getFirst();
        // system + 2 history + user
        assertThat(turns).hasSize(4);
        assertThat(turns.getFirst().role()).isEqualTo("system");
        String system = turns.getFirst().content();
        assertThat(system).contains("SINGLE_CHOICE");
        assertThat(system).contains("MULTIPLE_CHOICE");
        assertThat(system).contains("TRUE_FALSE_MATRIX");
        assertThat(system).contains("NUMERIC_FILL");
        assertThat(system).contains("Expected answer:");
        // Exact 4-char NUMERIC_FILL contract + import question cap (review P1/P2).
        assertThat(system).contains("EXACTLY four characters");
        assertThat(system).contains("at most 200 questions");
        assertThat(system).contains("at least TWO starred");

        assertThat(turns.get(1).role()).isEqualTo("user");
        assertThat(turns.get(1).content()).isEqualTo("hello");
        assertThat(turns.get(2).role()).isEqualTo("assistant");

        String user = turns.get(3).content();
        assertThat(user).contains("Create 5 questions");
        assertThat(user).contains(VALID_DOC.stripTrailing());
        assertThat(user).contains("### lecture.txt");
        assertThat(user).contains("Networking basics");
    }

    @Test
    void emptyCurrentMarkdownSentAsPlaceholder() {
        service.author(7L, 9L, request("Create quiz", "", null, null));
        String user = provider.calls.getFirst().getLast().content();
        assertThat(user).contains("(empty");
    }


    @Test
    void documentBudgetTruncatesLargeDocuments() {
        // 55k chars each: below the per-document cap (60k), above the total
        // budget (100k) when combined.
        service.author(7L, 9L, request("gen", "",
                List.of(
                        new AiAuthorDocument("a.txt", "a".repeat(55_000)),
                        new AiAuthorDocument("b.txt", "b".repeat(55_000))),
                null));

        String user = provider.calls.getFirst().getLast().content();
        // a.txt fits wholly (55k < 100k budget); b.txt is cut at the remaining
        // 45k budget — and a third document would be omitted entirely.
        assertThat(user).contains("### a.txt\n");
        assertThat(user).contains("### b.txt (truncated)");
        assertThat(user).doesNotContain("budget exhausted");
    }

    // ------------------------------------------------------------------
    // Candidate validation + repair
    // ------------------------------------------------------------------

    @Test
    void validCandidateReturnedAsValid() {
        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));

        assertThat(response.valid()).isTrue();
        assertThat(response.markdown()).isEqualTo(VALID_DOC.stripTrailing());
        assertThat(response.reply()).isEqualTo("Here is your document.");
        assertThat(response.diagnostics()).isEmpty();
        assertThat(provider.calls).hasSize(1);
    }

    @Test
    void codeFencedCandidateIsUnwrappedBeforeValidation() {
        provider = new RecordingProvider() {
            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                return new AiProviderResult("done", "```markdown\n" + VALID_DOC + "```");
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(response.valid()).isTrue();
        assertThat(response.markdown()).doesNotContain("```");
    }

    @Test
    void invalidCandidateTriggersOneRepairThenSucceeds() {
        provider = new RecordingProvider() {
            int call;

            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                call++;
                return call == 1
                        ? new AiProviderResult("draft", PARSE_INVALID_DOC)
                        : new AiProviderResult("fixed", VALID_DOC);
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(provider.calls).hasSize(2);
        assertThat(response.valid()).isTrue();
        // Repair turn carries the diagnostics and the previous candidate.
        String repair = provider.calls.get(1).getLast().content();
        assertThat(repair).contains("failed Quiz Markdown validation");
        assertThat(repair).contains(PARSE_INVALID_DOC);
    }

    @Test
    void contractInvalidCandidateRepairsToValid() {
        provider = new RecordingProvider() {
            int call;

            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                call++;
                return call == 1
                        ? new AiProviderResult("draft", CONTRACT_INVALID_DOC)
                        : new AiProviderResult("fixed", VALID_DOC);
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(provider.calls).hasSize(2);
        assertThat(response.valid()).isTrue();
        assertThat(provider.calls.get(1).getLast().content())
                .contains("MULTIPLE_CHOICE requires at least 2 correct options");
    }

    @Test
    void stillInvalidAfterRepairReturnsDiagnostics() {
        provider = new RecordingProvider() {
            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                return new AiProviderResult("draft", PARSE_INVALID_DOC);
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(provider.calls).hasSize(2); // bounded: exactly one repair
        assertThat(response.valid()).isFalse();
        assertThat(response.markdown()).isEqualTo(PARSE_INVALID_DOC.stripTrailing());
        assertThat(response.diagnostics()).isNotEmpty();
        for (AiAuthorDiagnostic diagnostic : response.diagnostics()) {
            assertThat(diagnostic.code()).isNotBlank();
            assertThat(diagnostic.message()).isNotBlank();
        }
    }

    @Test
    void repairDisabledWhenAttemptsZero() {
        AiAuthoringProperties noRepair = new AiAuthoringProperties(
                true, "openai", "u", "k", "m", 30, 5_000_000, 60_000, 5, 100_000,
                4_000, 20, 8_000, 262_144, 16_000, 0, 1_000, 3_600);
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(noRepair), noRepair);

        provider = new RecordingProvider() {
            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                return new AiProviderResult("draft", PARSE_INVALID_DOC);
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(noRepair), noRepair);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(provider.calls).hasSize(1);
        assertThat(response.valid()).isFalse();
    }

    // ------------------------------------------------------------------
    // Provider error mapping
    // ------------------------------------------------------------------

    @Test
    void providerRateLimitMapsTo429() {
        provider.nextFailureKind.set(1);
        assertThatThrownBy(() -> service.author(7L, 9L, request("gen", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_PROVIDER_RATE_LIMITED);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void providerTimeoutMapsTo504() {
        provider.nextFailureKind.set(2);
        assertThatThrownBy(() -> service.author(7L, 9L, request("gen", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_PROVIDER_TIMEOUT);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(504);
                });
    }

    @Test
    void providerUpstreamAndProtocolErrorsMapTo502() {
        provider.nextFailureKind.set(3);
        assertThatThrownBy(() -> service.author(7L, 9L, request("gen", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getHttpStatus().value()).isEqualTo(502));

        provider.nextFailureKind.set(4);
        assertThatThrownBy(() -> service.author(7L, 9L, request("gen", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class,
                        ex -> assertThat(ex.getHttpStatus().value()).isEqualTo(502));
    }

    // ------------------------------------------------------------------
    // Review findings: question-count preflight + per-user quota
    // ------------------------------------------------------------------

    @Test
    void candidateBeyondQuestionCapIsReportedInvalid() {
        StringBuilder huge = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            huge.append("Question ").append(i).append(" [SINGLE_CHOICE]: q").append(i).append("?\n")
                    .append("*A. 1\nB. 2\nC. 3\nD. 4\n");
        }
        provider = new RecordingProvider() {
            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                calls.add(List.copyOf(turns));
                return new AiProviderResult("too many", huge.toString());
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(properties), properties);

        AiAuthorResponse response = service.author(7L, 9L, request("gen", "", null, null));
        assertThat(response.valid()).isFalse();
        assertThat(response.diagnostics()).hasSize(1);
        assertThat(response.diagnostics().getFirst().code())
                .isEqualTo("QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS");
    }

    @Test
    void perUserQuotaRejectsExcessAuthoringCalls() {
        AiAuthoringProperties smallQuota = new AiAuthoringProperties(
                true, "openai", "https://api.example.test/v1", "test-key", "gpt-test",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 2, 3_600);
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(smallQuota), smallQuota);

        service.author(7L, 9L, request("gen", "", null, null));
        service.author(7L, 9L, request("more", "", null, null));
        assertThatThrownBy(() -> service.author(7L, 9L, request("again", "", null, null)))
                .isInstanceOfSatisfying(AiAuthoringException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(AiAuthoringErrorCode.AI_AUTHORING_RATE_LIMITED);
                    assertThat(ex.getHttpStatus().value()).isEqualTo(429);
                });
        // The rejected call never reached the provider.
        assertThat(provider.calls).hasSize(2);
        // A different user is unaffected.
        AiAuthorResponse other = service.author(8L, 9L, request("gen", "", null, null));
        assertThat(other.valid()).isTrue();
    }

    /**
     * Gate review P2: quota admission must be atomic per user — 20 concurrent
     * calls with a cap of 5 may let EXACTLY 5 reach the provider, never more.
     */
    @Test
    void concurrentAuthorCallsNeverExceedQuota() throws Exception {
        AiAuthoringProperties tinyQuota = new AiAuthoringProperties(
                true, "openai", "https://api.example.test/v1", "test-key", "gpt-test",
                30, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000, 262_144, 16_000, 1, 5, 3_600);
        java.util.concurrent.atomic.AtomicInteger providerAdmissions = new java.util.concurrent.atomic.AtomicInteger();
        provider = new RecordingProvider() {
            @Override
            public AiProviderResult complete(List<AiProviderTurn> turns) {
                // Count ONLY: the inherited calls list is a plain ArrayList,
                // and admitted calls run concurrently (P3 from re-review).
                providerAdmissions.incrementAndGet();
                return new AiProviderResult("ok", VALID_DOC);
            }
        };
        service = new AiAuthoringService(guard, provider, new AiDocumentExtractor(tinyQuota), tinyQuota);

        int threads = 20;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.author(7L, 9L, request("gen", "", null, null));
                    } catch (AiAuthoringException expected) {
                        // quota rejections land here
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(providerAdmissions.get()).isEqualTo(5);
    }
}
