package com.hhtuann.backend.academic.dto;

import java.time.Instant;

/**
 * Rich subject payload returned by the mutating endpoints
 * ({@code POST /api/subjects}, {@code PUT /api/subjects/{id}},
 * {@code PUT /api/subjects/{id}/status}). The list endpoint keeps the slimmer
 * {@link SubjectView} (no school/description/status) for backward compatibility
 * with the teacher question-bank / exam creation dropdowns.
 *
 * @param status the {@link com.hhtuann.backend.academic.domain.model.AcademicStatus}
 *               enum name (e.g. {@code "ACTIVE"}) — serialized as a plain string
 */
public record SubjectResponse(
        Long id,
        Long schoolId,
        Long gradeLevelId,
        String code,
        String name,
        String description,
        String status,
        Instant createdAt
) {}
