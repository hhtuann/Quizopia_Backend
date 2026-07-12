package com.quizopia.backend.attempt.dto;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Request body for {@code PUT /api/attempts/{attemptId}/answers} (autosave) — per frozen contract §5.1.
 *
 * <p>Either {@code attemptQuestionId} or {@code examQuestionId} identifies the question (at least one
 * required; if both present, {@code attemptQuestionId} is authoritative). {@code answerPayload} is
 * nullable (null = clear the answer). {@code sequenceNumber} must be {@code >= 1}.
 * {@code clientInstanceId} is optional (request-only; V9 has no attempt_answers.client_instance_id,
 * so it is validated but not persisted).
 */
public record SaveAnswerRequest(
        Long attemptQuestionId,
        Long examQuestionId,
        JsonNode answerPayload,
        long sequenceNumber,
        UUID clientInstanceId) {
}
