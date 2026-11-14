package com.quizopia.backend.attempt.dto;

import java.util.UUID;

/**
 * Request body for POST /api/exam-sessions/{sessionId}/attempts. Both fields
 * are optional. {@code monitoringReadinessLease} (P4-B, issue #85) is the
 * raw one-time readiness lease from a VERIFIED camera preflight — required
 * only for NEW attempts on monitored sessions; unmonitored sessions ignore
 * it silently (legacy clients unaffected).
 */
public record StartAttemptRequest(UUID clientInstanceId, String monitoringReadinessLease) {

    /** Legacy one-arg shape (P4-A clients/tests): no readiness lease. */
    public StartAttemptRequest(UUID clientInstanceId) {
        this(clientInstanceId, null);
    }

    public StartAttemptRequest {
        // null body → Jackson creates with null fields; {} → also null. Both valid.
    }
}
