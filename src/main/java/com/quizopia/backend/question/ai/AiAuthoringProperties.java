package com.quizopia.backend.question.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Phase 5 AI-assisted Quiz Markdown authoring
 * (issue #113). All values come from the server environment only — the
 * OpenAI API key never crosses to the client and is never logged.
 *
 * <ul>
 *   <li>{@code quizopia.ai.authoring.enabled} — feature flag (default
 *       {@code true}; the endpoints still refuse work when the key is
 *       absent, reporting {@code available=false} on the status endpoint);</li>
 *   <li>{@code quizopia.ai.authoring.provider} — {@code openai} (default,
 *       real provider) or {@code mock} (deterministic test provider, never
 *       enabled outside tests);</li>
 *   <li>{@code quizopia.ai.authoring.base-url} / {@code model} /
 *       {@code api-key} — OpenAI Responses API wiring, from
 *       {@code OPENAI_BASE_URL} / {@code OPENAI_MODEL} / {@code OPENAI_API_KEY};</li>
 *   <li>bounded sizes: file bytes, extracted chars, total document chars per
 *       authoring request, instruction / history / markdown caps, history
 *       length, output tokens, request timeout and repair attempts.</li>
 * </ul>
 *
 * @param enabled            feature flag
 * @param provider           {@code openai} or {@code mock}
 * @param maxMarkdownChars   max currentMarkdown length (mirrors import cap)
 * @param maxOutputTokens    provider max_output_tokens
 * @param repairAttempts     bounded auto-repair: 0 disables; any value >= 1
 *                           enables exactly ONE repair attempt
 * @param maxAuthorRequestsPerWindow per-user provider calls inside the window
 * @param authoringWindowSeconds     sliding window for the per-user quota
 * @param maxFileBytes       max single uploaded document (matches multipart cap)
 * @param maxExtractedChars  max chars kept from one extracted document
 * @param maxDocuments       max documents per authoring request
 * @param maxTotalDocumentChars max total document chars sent to the provider
 * @param maxInstructionChars max instruction length
 * @param maxHistoryMessages max chat history messages accepted
 * @param maxHistoryMessageChars max single history message length
 * @param maxMarkdownChars   max currentMarkdown length (mirrors import cap)
 * @param maxOutputTokens    provider max_output_tokens
 * @param repairAttempts     bounded auto-repair attempts for invalid candidates
 */
@ConfigurationProperties(prefix = "quizopia.ai.authoring")
public record AiAuthoringProperties(
        boolean enabled,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        long maxFileBytes,
        int maxExtractedChars,
        int maxDocuments,
        int maxTotalDocumentChars,
        int maxInstructionChars,
        int maxHistoryMessages,
        int maxHistoryMessageChars,
        int maxMarkdownChars,
        int maxOutputTokens,
        int repairAttempts,
        int maxAuthorRequestsPerWindow,
        int authoringWindowSeconds) {

    /** Feature is usable when the flag is on and a provider key is configured. */
    public boolean available() {
        if (!enabled) {
            return false;
        }
        if ("openai".equals(provider)) {
            return apiKey != null && !apiKey.isBlank();
        }
        // Deterministic mock exists only in automated tests (issue #113).
        return "mock".equals(provider);
    }
}
