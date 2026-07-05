package com.hhtuann.backend.attempt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response for POST /api/exam-sessions/{sessionId}/attempts (new or resume) — per frozen contract §4.4.
 * <p><b>DATA-LEAK SAFETY:</b> No answerKey, isCorrect, correct option, explanation, TF ladder, or score.
 * Questions contain only rendering data (content, type, options without isCorrect).
 */
public record StartAttemptResponse(
        Long attemptId, Long sessionId, Integer attemptNumber, String status,
        Instant startedAt, Instant deadlineAt, Instant serverTime,
        boolean resumed, Integer maxAttempts,
        List<QuestionView> questions) {

    public record QuestionView(
            Long attemptQuestionId, Long examQuestionId, String questionType,
            Integer displayOrder, String content, BigDecimal defaultPoints,
            List<OptionView> options) {

        public record OptionView(String optionKey, String content, Integer position) {}
    }
}
