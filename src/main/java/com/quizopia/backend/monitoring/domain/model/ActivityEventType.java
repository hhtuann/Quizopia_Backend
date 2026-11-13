package com.quizopia.backend.monitoring.domain.model;

import java.util.Set;

/**
 * Activity event types (P4-A2/#81 + P4-A4/#83).
 *
 * <p>The P4-A set is frozen by the V34 DB CHECK constraint; the enum mirrors
 * it exactly. Media/AI types (CAMERA_*, AI_ALERT_CREATED, RECORDING_*,
 * EVIDENCE_AVAILABLE) are deliberately ABSENT until P4-B/C/D — fake media
 * events cannot be persisted in P4-A.
 *
 * <p>{@link #CLIENT_TYPES} is the ingest allowlist: only client-observed
 * types are accepted from the browser. Server types (lifecycle,
 * ANSWER_ACCEPTED) are written exclusively by backend services inside their
 * transactions — a client can never forge a server-confirmed record.
 */
public enum ActivityEventType {
    // Server-confirmed attempt lifecycle
    ATTEMPT_STARTED,
    ATTEMPT_RESUMED,
    ATTEMPT_SUBMITTED,
    ATTEMPT_TIMED_OUT,

    // Server-confirmed autosave acceptance (P4-A3)
    ANSWER_ACCEPTED,

    // Client-observed answer interactions
    ANSWER_SELECTED,
    ANSWER_CHANGED,
    ANSWER_CLEARED,

    // Client-observed navigation/flags/autosave telemetry
    QUESTION_NAVIGATED,
    QUESTION_FLAGGED,
    QUESTION_UNFLAGGED,
    AUTOSAVE_FAILED,

    // Client-observed browser telemetry
    TAB_HIDDEN,
    TAB_VISIBLE,
    WINDOW_FOCUS_LOST,
    WINDOW_FOCUS_GAINED,
    PAGE_RELOADED,
    CONNECTION_LOST,
    CONNECTION_RESTORED;

    /** Types the browser ingest endpoint accepts (source=CLIENT only). */
    public static final Set<ActivityEventType> CLIENT_TYPES = Set.of(
            ANSWER_SELECTED, ANSWER_CHANGED, ANSWER_CLEARED,
            QUESTION_NAVIGATED, QUESTION_FLAGGED, QUESTION_UNFLAGGED, AUTOSAVE_FAILED,
            TAB_HIDDEN, TAB_VISIBLE, WINDOW_FOCUS_LOST, WINDOW_FOCUS_GAINED,
            PAGE_RELOADED, CONNECTION_LOST, CONNECTION_RESTORED);

    public boolean isClientType() {
        return CLIENT_TYPES.contains(this);
    }

    /** Strict parser: unknown strings yield null (never an exception). */
    public static ActivityEventType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ActivityEventType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
