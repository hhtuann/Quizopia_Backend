package com.quizopia.backend.monitoring.domain.model;

import java.util.Set;

/**
 * Activity event types (P4-A2/#81 + P4-A4/#83; camera lifecycle P4-B/#85+/#87).
 *
 * <p>The persistable set is frozen by the V34/V37 DB CHECK constraints; the
 * enum mirrors them exactly. The P4-B camera lifecycle types
 * (CAMERA_*, MONITORING_GAP) are SERVER-confirmed only: they are written
 * exclusively by the backend (webhook routing / reconciliation / revocation)
 * inside their transactions. Future media/AI types (AI_ALERT_CREATED,
 * RECORDING_*, EVIDENCE_AVAILABLE) stay absent until P4-C/D.
 *
 * <p>{@link #CLIENT_TYPES} is the ingest allowlist: only client-observed
 * types are accepted from the browser. Server types (lifecycle,
 * ANSWER_ACCEPTED, camera lifecycle) are written exclusively by backend
 * services inside their transactions — a client can never forge a
 * server-confirmed record.
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
    CONNECTION_RESTORED,

    // Server-confirmed camera lifecycle (P4-B, issues #85/#87; V37 CHECK).
    // Written by webhook routing + reconciliation only — never client-ingestable
    // (CLIENT_TYPES below keeps rejecting them).
    CAMERA_CONNECTED,
    CAMERA_DISCONNECTED,
    CAMERA_RECONNECTED,
    CAMERA_REVOKED,
    MONITORING_GAP;

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
