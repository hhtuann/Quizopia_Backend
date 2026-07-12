package com.quizopia.backend.attempt.dto;

import java.util.UUID;

/** Request body for POST /api/exam-sessions/{sessionId}/attempts. clientInstanceId is optional. */
public record StartAttemptRequest(UUID clientInstanceId) {
    public StartAttemptRequest {
        // null body → Jackson creates with null fields; {} → also null. Both valid.
    }
}
