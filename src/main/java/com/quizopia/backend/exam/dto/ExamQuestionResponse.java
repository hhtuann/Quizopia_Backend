package com.quizopia.backend.exam.dto;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;

public record ExamQuestionResponse(
        Long id, Integer position,
        Long sourceQuestionId, Long sourceQuestionVersionId,
        String questionCode, String questionType,
        String content, BigDecimal defaultPoints,
        String difficulty, String explanation,
        JsonNode answerKey, JsonNode metadata,
        Long sectionId,
        java.util.List<ExamQuestionOptionResponse> options
) {}
