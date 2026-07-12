package com.quizopia.backend.attempt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response for {@code PUT /api/attempts/{attemptId}/answers} (autosave) — per frozen contract §5.3.
 *
 * <p><b>DATA-LEAK SAFETY:</b> carries NO answerPayload, attemptAnswerId, clientInstanceId, student/school
 * identity, answer key, or score/grade. {@code reason} is null (and omitted via {@code NON_NULL}) when
 * {@code accepted=true}. The sequence/savedAt are the ACTUAL DB row values after the UPSERT (current
 * state, whether the save was accepted or stale).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveAnswerResponse(
        boolean accepted,
        String reason,
        Long attemptQuestionId,
        long currentSequenceNumber,
        Instant savedAt,
        Instant serverTime) {
}
