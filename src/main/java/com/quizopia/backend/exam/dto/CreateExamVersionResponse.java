package com.quizopia.backend.exam.dto;

/**
 * Response for {@code POST /api/exams/{examId}/versions} (HTTP 201). Carries NO answer
 * data — only the new DRAFT's version number, its status, and the version number it was
 * cloned from.
 */
public record CreateExamVersionResponse(
        Integer versionNumber,
        String status,
        Integer clonedFrom
) {
}
