package com.hhtuann.backend.exam.dto;

public record ExamQuestionOptionResponse(
        Long id, String optionKey, String content,
        Boolean isCorrect, Integer position
) {}
