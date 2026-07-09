package com.hhtuann.backend.academic.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/grade-levels} — a flat list. Grade levels
 * feed subject-creation dropdowns, so pagination is not needed.
 */
public record GradeLevelListResponse(List<GradeLevelView> items) {}
