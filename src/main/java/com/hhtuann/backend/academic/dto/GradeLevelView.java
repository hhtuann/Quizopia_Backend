package com.hhtuann.backend.academic.dto;

/**
 * Grade-level metadata returned by {@code GET /api/grade-levels}. School-scoped
 * only; never carries sensitive data.
 *
 * @param sortOrder ascending display order within the school
 */
public record GradeLevelView(
        Long id,
        String code,
        String name,
        Integer sortOrder
) {}
