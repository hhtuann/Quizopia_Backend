package com.quizopia.backend.academic.dto;

/**
 * Subject metadata returned by {@code GET /api/subjects}. Only public
 * school-scoped metadata; never carries sensitive data.
 *
 * @param id           subject id
 * @param code         stable subject code (e.g. {@code "GEN-MATH"})
 * @param name         display name (e.g. {@code "Toán"})
 * @param gradeLevelId the subject's grade level within the school — lets the
 *                     frontend group/filter a subject dropdown
 */
public record SubjectView(
        Long id,
        String code,
        String name,
        Long gradeLevelId
) {}
