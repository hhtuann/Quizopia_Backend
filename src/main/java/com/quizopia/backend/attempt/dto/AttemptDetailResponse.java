package com.quizopia.backend.attempt.dto;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/attempts/{attemptId}} — per frozen contract §8.2.
 *
 * <p><b>DATA-LEAK SAFETY:</b> carries NO answerKey, expectedAnswer, requiredInputLength, isCorrect,
 * correct option, explanation, tfMatrixScoring, grade, score, clientInstanceId,
 * submissionIdempotencyKey, studentProfileId, or schoolId. Only student-safe rendering data.
 * {@link OptionView#position} is the render index (0..n-1) in the persisted option_order — never the
 * source DB position.
 */
public record AttemptDetailResponse(
        Long attemptId, Long sessionId, Integer attemptNumber, String status,
        Instant startedAt, Instant deadlineAt, Instant submittedAt, Instant serverTime,
        int answeredCount, int totalQuestions,
        List<QuestionView> questions) {

    public record QuestionView(
            Long attemptQuestionId, Long examQuestionId, String questionType,
            Integer displayOrder, String content, BigDecimal defaultPoints,
            List<OptionView> options,
            SavedAnswerView savedAnswer) {}

    public record OptionView(String optionKey, String content, Integer position) {}

    /** Saved answer for one question. answerPayload may be null (cleared answer); the row still exists. */
    public record SavedAnswerView(JsonNode answerPayload, Long sequenceNumber) {}
}
