package com.quizopia.backend.question.ai.provider;

import com.quizopia.backend.question.ai.AiAuthoringProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenAiResponsesProvider} against an in-process JDK
 * {@link HttpServer} faking the OpenAI Responses API: request shape (model,
 * strict JSON schema, bearer key, input turns), happy-path parsing, status
 * mapping (429/401/500), timeout mapping, and malformed-body mapping.
 */
class OpenAiResponsesProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        InetSocketAddress address = server.getAddress();
        baseUrl = "http://127.0.0.1:" + address.getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(int status, String body) {
        server.createContext("/v1/responses", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private OpenAiResponsesProvider provider(int timeoutSeconds) {
        AiAuthoringProperties properties = new AiAuthoringProperties(
                true, "openai", baseUrl, "test-key-123", "gpt-5.4-mini",
                timeoutSeconds, 5_000_000, 60_000, 5, 100_000, 4_000, 20, 8_000,
                262_144, 16_000, 1, 1_000, 3_600);
        return new OpenAiResponsesProvider(properties);
    }

    private static List<AiProviderTurn> turns() {
        return List.of(
                new AiProviderTurn("system", "grammar rules"),
                new AiProviderTurn("user", "make questions"));
    }

    private static String responsesBody(String outputText) {
        String escaped = outputText.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"id":"resp_1","status":"completed","output":[
                  {"type":"reasoning","summary":[]},
                  {"type":"message","role":"assistant","status":"completed","content":[
                    {"type":"output_text","text":"%s"}]}
                ]}
                """.formatted(escaped);
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void happyPathSendsStrictSchemaAndParsesResult() {
        respond(200, responsesBody("{\"reply\":\"Created 5 questions.\",\"markdown\":\"Question 1 ...\"}"));

        AiProviderResult result = provider(10).complete(turns());

        assertThat(result.reply()).isEqualTo("Created 5 questions.");
        assertThat(result.markdown()).isEqualTo("Question 1 ...");

        String request = lastRequestBody.get();
        assertThat(request).contains("\"model\":\"gpt-5.4-mini\"");
        assertThat(request).contains("\"type\":\"json_schema\"");
        assertThat(request).contains("\"strict\":true");
        assertThat(request).contains("\"reply\"");
        assertThat(request).contains("\"markdown\"");
        assertThat(request).contains("\"role\":\"system\"");
        assertThat(request).contains("grammar rules");
        assertThat(request).contains("\"store\":false");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer test-key-123");
    }

    @Test
    void aggregatesMultipleOutputTextParts() {
        String part1 = escapeJson("{\"reply\":\"part\",");
        String part2 = escapeJson(" \"markdown\":\"doc\"}");
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"" + part1 + "\"},"
                + "{\"type\":\"output_text\",\"text\":\"" + part2 + "\"}]}]}";
        respond(200, body);

        AiProviderResult result = provider(10).complete(turns());
        assertThat(result.reply()).isEqualTo("part");
        assertThat(result.markdown()).isEqualTo("doc");
    }

    @Test
    void maps429ToRateLimited() {
        respond(429, "{\"error\":{\"message\":\"Rate limit reached\"}}");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.RATE_LIMITED));
    }

    @Test
    void mapsUnauthorizedToUpstreamErrorWithoutLeakingBody() {
        respond(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-test-key-123\"}}");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.UPSTREAM_ERROR);
                    // The client-visible message never contains upstream body text.
                    assertThat(ex.getMessage()).doesNotContain("sk-test-key-123");
                });
    }

    @Test
    void maps500ToUpstreamError() {
        respond(500, "{\"error\":{\"message\":\"internal\"}}");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.UPSTREAM_ERROR));
    }

    @Test
    void mapsTimeout() {
        server.createContext("/v1/responses", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                // closed during sleep
            }
        });
        assertThatThrownBy(() -> provider(1).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.TIMEOUT));
    }

    @Test
    void mapsMalformedJsonBodyToProtocol() {
        respond(200, "not json at all");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.PROTOCOL));
    }

    @Test
    void mapsMissingStructuredFieldsToProtocol() {
        respond(200, responsesBody("{\"reply\":\"no markdown field\"}"));
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.PROTOCOL));
    }

    @Test
    void mapsEmptyOutputToProtocol() {
        respond(200, "{\"status\":\"completed\",\"output\":[]}");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.PROTOCOL));
    }

    @Test
    void mapsIncompleteStatusToProtocol() {
        respond(200, "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"reply\\\":\\\"r\\\",\\\"markdown\\\":\\\"m\\\"}\"}]}]}");
        assertThatThrownBy(() -> provider(10).complete(turns()))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.getKind()).isEqualTo(AiProviderException.Kind.PROTOCOL));
    }
}
