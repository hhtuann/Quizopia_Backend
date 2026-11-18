package com.quizopia.backend.question.ai.provider;

import com.quizopia.backend.question.ai.AiAuthoringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI Responses API adapter ({@code POST {base}/responses}) with JSON
 * Schema Structured Outputs, per the binding provider decision of 2026-11-18
 * (issue #113): {@code OPENAI_BASE_URL=https://api.openai.com/v1}, model
 * {@code gpt-5.4-mini} (configurable), server-only key.
 *
 * <p>Security notes:
 * <ul>
 *   <li>the API key is sent only in the {@code Authorization} header and is
 *       never logged;</li>
 *   <li>upstream error bodies are NOT propagated to clients and only the
 *       status/kind is logged (no document text, no key);</li>
 *   <li>timeouts are enforced by {@link HttpRequest#timeout} so a hanging
 *       upstream cannot pin request threads.</li>
 * </ul>
 */
public class OpenAiResponsesProvider implements AiQuizProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesProvider.class);

    /** Maximum upstream error body kept for diagnostics logging (bytes). */
    private static final int MAX_ERROR_BODY_SNIPPET = 512;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiAuthoringProperties properties;
    private final HttpClient httpClient;

    public OpenAiResponsesProvider(AiAuthoringProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public AiProviderResult complete(List<AiProviderTurn> turns) {
        String body = buildRequestBody(turns);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/responses"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiProviderException(AiProviderException.Kind.TIMEOUT,
                    "AI provider request timed out", e);
        } catch (ConnectException e) {
            throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR,
                    "AI provider connection failed", e);
        } catch (IOException e) {
            throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR,
                    "AI provider request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR,
                    "AI provider request interrupted", e);
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new AiProviderException(AiProviderException.Kind.RATE_LIMITED,
                    "AI provider rate limit (429)");
        }
        if (status < 200 || status >= 300) {
            log.warn("OpenAI responses call failed: status={} bodySnippet={}",
                    status, errorSnippet(response.body()));
            throw new AiProviderException(AiProviderException.Kind.UPSTREAM_ERROR,
                    "AI provider error (HTTP " + status + ")");
        }
        return parseResult(response.body());
    }

    /** Builds the Responses API JSON body with a strict {reply, markdown} schema. */
    private String buildRequestBody(List<AiProviderTurn> turns) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", properties.model());

        ArrayNode input = root.putArray("input");
        for (AiProviderTurn turn : turns) {
            ObjectNode item = input.addObject();
            item.put("role", turn.role());
            item.put("content", turn.content());
        }

        ObjectNode format = root.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", "quiz_authoring_result");
        format.put("strict", true);
        ObjectNode schema = format.putObject("schema");
        schema.put("type", "object");
        ObjectNode schemaProperties = schema.putObject("properties");
        schemaProperties.putObject("reply").put("type", "string");
        schemaProperties.putObject("markdown").put("type", "string");
        schema.putArray("required").add("reply").add("markdown");
        schema.put("additionalProperties", false);

        root.put("max_output_tokens", properties.maxOutputTokens());
        root.put("store", false);
        return root.toString();
    }

    /**
     * Extracts the aggregated {@code output_text} from a Responses API body
     * and parses the structured {reply, markdown} JSON it must contain.
     */
    static AiProviderResult parseResult(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new AiProviderException(AiProviderException.Kind.PROTOCOL,
                    "AI provider returned a non-JSON body", e);
        }

        String status = root.path("status").asText("");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
        }
        if ("incomplete".equals(status)) {
            throw new AiProviderException(AiProviderException.Kind.PROTOCOL,
                    "AI provider response was incomplete (output token limit)");
        }
        if (text.isEmpty()) {
            throw new AiProviderException(AiProviderException.Kind.PROTOCOL,
                    "AI provider returned no output text");
        }

        JsonNode structured;
        try {
            structured = JSON.readTree(text.toString());
        } catch (Exception e) {
            throw new AiProviderException(AiProviderException.Kind.PROTOCOL,
                    "AI provider output was not the requested JSON", e);
        }
        String reply = structured.path("reply").asText(null);
        String markdown = structured.path("markdown").asText(null);
        if (reply == null || markdown == null) {
            throw new AiProviderException(AiProviderException.Kind.PROTOCOL,
                    "AI provider JSON was missing reply/markdown fields");
        }
        return new AiProviderResult(reply, markdown);
    }

    private static String errorSnippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_BODY_SNIPPET ? flat : flat.substring(0, MAX_ERROR_BODY_SNIPPET);
    }
}
