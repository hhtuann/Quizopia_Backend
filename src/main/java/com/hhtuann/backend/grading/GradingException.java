package com.hhtuann.backend.grading;

/**
 * Domain exception raised by the grading engine for configuration/data inconsistency (§4/§10). Carries a
 * {@link GradingErrorCode} for the API-layer HTTP mapping; the message is internal-only and must never reach
 * a client verbatim (the handler returns a sanitized {@code ApiError}). No answer-key content is placed in
 * the message — it would leak the key through error responses/logs.
 */
public class GradingException extends RuntimeException {

    private final GradingErrorCode code;

    public GradingException(GradingErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public GradingErrorCode code() {
        return code;
    }
}
