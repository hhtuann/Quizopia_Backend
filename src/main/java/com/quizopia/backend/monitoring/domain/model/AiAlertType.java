package com.quizopia.backend.monitoring.domain.model;

/**
 * Server-aggregated AI alert types (P4-C, CONTRACT §5, V38 CHECK;
 * {@code LOOKING_AWAY} added by V43 — an estimated gaze deviation is the
 * most inconclusive of the observations: human-review context only).
 *
 * <p>DETECTED / NOT_VISIBLE are the server-aggregated vocabulary — a raw
 * client observation ({@link AiDetectionType} *_OBSERVED) maps 1:1 onto
 * exactly one alert type. Alerts are observations for the teacher feed:
 * no verdict, no score impact (CONTRACT §8).
 */
public enum AiAlertType {
    PHONE_DETECTED,
    SCREEN_DETECTED,
    MULTIPLE_PERSONS_DETECTED,
    PERSON_NOT_VISIBLE,
    LOOKING_AWAY;

    /** The alert type a detection observation aggregates into. */
    public static AiAlertType fromDetection(AiDetectionType detectionType) {
        return switch (detectionType) {
            case PHONE_OBSERVED -> PHONE_DETECTED;
            case SCREEN_OBSERVED -> SCREEN_DETECTED;
            case MULTIPLE_PERSONS_OBSERVED -> MULTIPLE_PERSONS_DETECTED;
            case PERSON_NOT_OBSERVED -> PERSON_NOT_VISIBLE;
            case LOOKING_AWAY_OBSERVED -> LOOKING_AWAY;
        };
    }
}
