package com.quizopia.backend.community.dto;

/**
 * Public category entry (ADR-006 §7 Rev 4): {@code id} is the
 * {@code categoryId} catalog filter key — taxonomy ids are public catalog
 * identity per the {@code /api/exam-purposes} platform precedent, not
 * internal content ids. Ordered by {@code position} (deterministic
 * {@code code} tiebreak) by the migration-managed seed.
 */
public record PublicCategoryResponse(
        Long id,
        String code,
        String title) {
}
