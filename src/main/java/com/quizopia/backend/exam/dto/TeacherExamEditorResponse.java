package com.quizopia.backend.exam.dto;

import com.quizopia.backend.question.dto.SubjectSummary;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Editor/management response for {@code GET /api/exams/{examId}} — the single
 * management view of an exam (Task B redesign): identity + settings +
 * composition + publish metadata. For PUBLISHED exams the composition is the
 * immutable published snapshot (read-only).
 */
public record TeacherExamEditorResponse(
        Long id, String code, String title, String description,
        SubjectSummary subject, ExamPurposeSummary purpose,
        String status,
        Integer durationMinutes, String instructions,
        JsonNode tfMatrixScoring,
        List<ExamSectionResponse> composition,
        BigDecimal totalPoints,
        Instant publishedAt,
        Long sourceExamId,
        Integer examVersion,
        Instant createdAt, Instant updatedAt
) {}
