package com.quizopia.backend.exam.dto;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record ExamDraftVersionResponse(
        Integer versionNumber, String status,
        Integer durationMinutes, String instructions,
        JsonNode tfMatrixScoring,
        List<ExamSectionResponse> sections
) {}
