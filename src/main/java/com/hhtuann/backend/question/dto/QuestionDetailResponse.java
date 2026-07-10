package com.hhtuann.backend.question.dto;

import com.hhtuann.backend.question.domain.model.QuestionDifficulty;
import com.hhtuann.backend.question.domain.model.QuestionType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full detail of a question (latest version) for the teacher edit form.
 * Carries options + answerKey — TEACHER-ONLY (never sent to students).
 */
public record QuestionDetailResponse(
        Long id,
        String code,
        QuestionType questionType,
        String content,
        QuestionDifficulty difficulty,
        String explanation,
        BigDecimal defaultPoints,
        Integer currentVersionNumber,
        List<OptionView> options,
        JsonNode answerKey
) {
    public record OptionView(String optionKey, String content, Boolean isCorrect, Integer position) {}
}
