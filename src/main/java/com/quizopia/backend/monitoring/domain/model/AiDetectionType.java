package com.quizopia.backend.monitoring.domain.model;

/**
 * Client-observation detection event types (P4-C, CONTRACT §3.1, V38 CHECK).
 *
 * <p>Naming is deliberate: the word <b>OBSERVED</b> marks raw model
 * observations from a detector (browser ORT-Web or the future worker);
 * <b>DETECTED</b> is reserved for server-aggregated alerts
 * ({@link AiAlertType}). Per ADR-008 D8 an observation is never a verified
 * misconduct fact — quarantine and aggregation semantics live in the
 * ingest/aggregation services, not here.
 */
public enum AiDetectionType {
    PHONE_OBSERVED,
    SCREEN_OBSERVED,
    MULTIPLE_PERSONS_OBSERVED,
    PERSON_NOT_OBSERVED;

    /** Strict parser: unknown strings yield null (never an exception). */
    public static AiDetectionType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AiDetectionType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
