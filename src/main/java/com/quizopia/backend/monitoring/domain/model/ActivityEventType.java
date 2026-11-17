package com.quizopia.backend.monitoring.domain.model;

import java.util.Set;

/**
 * Activity event types (P4-A2/#81 + P4-A4/#83; camera lifecycle P4-B/#85+/#87;
 * AI alert lifecycle P4-C/#90).
 *
 * <p>The persistable set is frozen by the V34/V37/V38 DB CHECK constraints; the
 * enum mirrors them exactly. The P4-B camera lifecycle types
 * (CAMERA_*, MONITORING_GAP) and the P4-C AI alert types (AI_ALERT_*) are
 * SERVER-confirmed only: they are written exclusively by the backend
 * (webhook routing / reconciliation / revocation / AI aggregation) inside
 * their transactions. The P4-D recording/evidence types (RECORDING_*,
 * EVIDENCE_*) are likewise SERVER-confirmed only.
 *
 * <p>{@link #CLIENT_TYPES} is the ingest allowlist: only client-observed
 * types are accepted from the browser. Server types (lifecycle,
 * ANSWER_ACCEPTED, camera lifecycle, AI alert lifecycle) are written
 * exclusively by backend services inside their transactions — a client can
 * never forge a server-confirmed record.
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
    MONITORING_GAP,

    // Server-confirmed AI alert lifecycle (P4-C, issue #90; V38 CHECK).
    // Written by AiAlertAggregationService only — never client-ingestable
    // (CLIENT_TYPES below keeps rejecting them).
    AI_ALERT_RAISED,
    AI_ALERT_UPDATED,

    // Server-confirmed recording + evidence lifecycle (P4-D, issues
    // #91-#94; V40 CHECK). Written by the recording coordinator, the
    // snapshot/clip services and the retention job only — never
    // client-ingestable.
    RECORDING_STARTED,
    RECORDING_STOPPED,
    RECORDING_FAILED,
    EVIDENCE_SNAPSHOT_UPLOADED,
    EVIDENCE_SNAPSHOT_REJECTED,
    EVIDENCE_CLIP_REQUESTED,
    EVIDENCE_CLIP_READY,
    EVIDENCE_CLIP_FAILED,

    // Server-confirmed teacher review lifecycle (P4-E, issues #95/#96;
    // V42 CHECK). Written by AlertReviewService only — never
    // client-ingestable (CLIENT_TYPES below keeps rejecting it).
    ALERT_REVIEW_RECORDED;

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
