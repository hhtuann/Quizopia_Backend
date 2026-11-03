package com.quizopia.backend.question;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParseResult;
import com.quizopia.backend.question.domain.markdown.QuizMarkdownParser;
import com.quizopia.backend.question.domain.markdown.QuizOption;
import com.quizopia.backend.question.domain.markdown.QuizQuestion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cross-language conformance harness (P2-M1-D, Quizopia_System issue #9).
 *
 * <p>Executes the real {@link QuizMarkdownParser} against the canonical fixture file shared
 * byte-identically with the frontend repository
 * ({@code lib/quiz-markdown/conformance/quiz-markdown-conformance-fixtures.json} in
 * {@code hhtuann/quizopia_frontend}); SHA-256 parity is part of the P2-M1 verification package.
 *
 * <p>Comparison policy (documented in the fixture file): valid cases compare the full normalized
 * question structure; invalid cases compare diagnostics as an unordered set of
 * {@code code|questionNumber|line|column} tuples, because Java and TypeScript intentionally emit
 * some diagnostics in a different order.
 */
class QuizMarkdownConformanceFixturesTest {

    private static final String FIXTURE_RESOURCE = "/markdown/quiz-markdown-conformance-fixtures.json";

    private final QuizMarkdownParser parser = new QuizMarkdownParser();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Fixtures(int version, List<FixtureCase> cases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FixtureCase(String id, String source, boolean valid, List<FixtureQuestion> questions,
            List<FixtureDiagnostic> diagnostics) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FixtureQuestion(int number, String type, String stem, List<FixtureOption> options,
            String numericAnswer, String explanation) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FixtureOption(String label, boolean correct, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FixtureDiagnostic(String code, Integer questionNumber, int line, int column) {}

    private static List<FixtureCase> loadCases() {
        try (InputStream input =
                QuizMarkdownConformanceFixturesTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            Objects.requireNonNull(input, "missing fixture resource " + FIXTURE_RESOURCE);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return JsonMapper.builder().build().readValue(json, Fixtures.class).cases();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load conformance fixtures", exception);
        }
    }

    private static String diagnosticKey(
            String code, Integer questionNumber, int line, int column) {
        return code + "|" + questionNumber + "|" + line + "|" + column;
    }

    @Test
    void canonicalFixturesConformBetweenSpecificationAndJavaParser() {
        List<FixtureCase> cases = loadCases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);

        for (FixtureCase fixtureCase : cases) {
            QuizMarkdownParseResult result = parser.parse(fixtureCase.source());

            assertThat(result.isValid())
                    .as("%s: validity", fixtureCase.id())
                    .isEqualTo(fixtureCase.valid());

            if (fixtureCase.valid()) {
                assertThat(result.content())
                        .as("%s: valid source must expose content", fixtureCase.id())
                        .isPresent();
                List<QuizQuestion> actual = result.content().orElseThrow().questions();
                assertThat(actual).as("%s: question count", fixtureCase.id())
                        .hasSameSizeAs(fixtureCase.questions());
                for (int index = 0; index < fixtureCase.questions().size(); index++) {
                    assertQuestion(fixtureCase, fixtureCase.questions().get(index), actual.get(index));
                }
            } else {
                // Java is all-or-nothing: invalid sources never expose content.
                assertThat(result.content())
                        .as("%s: invalid source must not expose content", fixtureCase.id())
                        .isEmpty();

                List<String> expected = fixtureCase.diagnostics().stream()
                        .map(diagnostic -> diagnosticKey(diagnostic.code(),
                                diagnostic.questionNumber(), diagnostic.line(), diagnostic.column()))
                        .sorted()
                        .distinct()
                        .toList();
                List<String> actual = result.errors().stream()
                        .map(error -> diagnosticKey(error.code(), error.questionNumber(),
                                error.line(), error.column()))
                        .sorted()
                        .distinct()
                        .toList();
                assertThat(actual)
                        .as("%s: diagnostics set (code|question|line|column)", fixtureCase.id())
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    private static void assertQuestion(FixtureCase fixtureCase, FixtureQuestion expected, QuizQuestion actual) {
        String context = fixtureCase.id() + " question " + expected.number();
        assertThat(actual.number()).as("%s: number", context).isEqualTo(expected.number());
        assertThat(actual.type().name()).as("%s: type", context).isEqualTo(expected.type());
        assertThat(actual.stemMarkdown()).as("%s: stem", context).isEqualTo(expected.stem());
        assertThat(actual.numericAnswer()).as("%s: numericAnswer", context)
                .isEqualTo(expected.numericAnswer());

        assertThat(actual.explanationMarkdown()).as("%s: explanation", context)
                .isEqualTo(expected.explanation());
        assertThat(actual.options()).as("%s: option count", context)
                .hasSameSizeAs(expected.options());
        for (int index = 0; index < expected.options().size(); index++) {
            FixtureOption expectedOption = expected.options().get(index);
            QuizOption actualOption = actual.options().get(index);
            assertThat(actualOption.label().name())
                    .as("%s: option %d label", context, index)
                    .isEqualTo(expectedOption.label());
            assertThat(actualOption.correct())
                    .as("%s: option %d correct", context, index)
                    .isEqualTo(expectedOption.correct());
            assertThat(actualOption.markdown())
                    .as("%s: option %d content", context, index)
                    .isEqualTo(expectedOption.content());
        }
    }

    /** Mirrors the frontend harness: this copy must be the canonical shared file. */
    @Test
    void fixtureBytesMatchTheCanonicalCrossLanguageSha256() {
        byte[] hash;
        try (InputStream input =
                QuizMarkdownConformanceFixturesTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            Objects.requireNonNull(input, "missing fixture resource " + FIXTURE_RESOURCE);
            hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes());
        } catch (IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("cannot hash conformance fixtures", exception);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) {
            hex.append(String.format("%02x", value));
        }
        // Kept in sync with lib/quiz-markdown/conformance/cross-language-conformance.test.ts
        // in hhtuann/quizopia_frontend.
        assertThat(hex.toString())
                .isEqualTo("3baa1a74674b7b4b2448d6910bafd299eaa1710b60c7f0d90a2c183b8be7be03");
    }
}
