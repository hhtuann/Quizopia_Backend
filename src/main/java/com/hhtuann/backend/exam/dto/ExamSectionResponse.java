package com.hhtuann.backend.exam.dto;

import java.util.List;

public record ExamSectionResponse(
        Long id, Integer position, String title, String instructions,
        List<ExamQuestionResponse> questions
) {}
