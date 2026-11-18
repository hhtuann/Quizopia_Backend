package com.quizopia.backend.question.ai.provider;

/**
 * Provider call failure. Carries only a coarse {@link Kind} — the upstream
 * body is logged server-side at most as a short, redacted snippet and never
 * propagated to the client response.
 */
public class AiProviderException extends RuntimeException {

    public enum Kind {
        /** Upstream 429 — pass through as 429. */
        RATE_LIMITED,
        /** Request/connect/read timeout — 504. */
        TIMEOUT,
        /** Upstream 4xx/5xx (auth, quota, server error) — 502. */
        UPSTREAM_ERROR,
        /** 2xx but unusable payload (no text, non-JSON, schema violation) — 502. */
        PROTOCOL
    }

    private final Kind kind;

    public AiProviderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
