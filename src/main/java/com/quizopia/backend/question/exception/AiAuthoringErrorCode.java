package com.quizopia.backend.question.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes for the AI-assisted Quiz Markdown authoring API
 * (Phase 5, issue #113). Same convention as {@link QuestionErrorCode}.
 *
 * <p>Provider-side failures never leak the upstream body or any credential;
 * the message is a fixed, server-authored string.
 */
public enum AiAuthoringErrorCode {

    /** Request failed local validation (caps, shapes, blank instruction). 400. */
    AI_AUTHORING_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "The AI authoring request is invalid"),

    /** Feature flag off or provider key absent. 503. */
    AI_AUTHORING_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "AI-assisted authoring is not available"),

    /** Unsupported extension / not a parsable document. 400/413/415. */
    AI_DOCUMENT_INVALID(HttpStatus.BAD_REQUEST, "The uploaded document cannot be processed"),
    AI_DOCUMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded document exceeds the size limit"),
    AI_DOCUMENT_UNSUPPORTED_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only .txt, .pdf, .docx and .pptx documents are accepted"),

    /** Too many authoring requests from this user (in-memory bucket). 429. */
    AI_AUTHORING_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many AI authoring requests — try again later"),
    AI_DOCUMENT_NO_TEXT(HttpStatus.UNPROCESSABLE_ENTITY, "No extractable text found (scanned/image-only PDFs are not supported)"),

    /** Upstream provider failures (never retried silently). */
    AI_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "The AI provider request failed"),
    AI_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "The AI provider timed out"),
    AI_PROVIDER_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "The AI provider is rate limiting requests");

    private final HttpStatus status;
    private final String defaultMessage;

    AiAuthoringErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }

    public String code() { return name(); }

    public String defaultMessage() { return defaultMessage; }
}
