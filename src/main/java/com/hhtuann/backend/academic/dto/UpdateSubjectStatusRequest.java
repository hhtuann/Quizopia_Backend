package com.hhtuann.backend.academic.dto;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /api/subjects/{id}/status} (SUBJECT_STATUS_UPDATE).
 * Accepts the full {@link AcademicStatus} enum ({@code ACTIVE}, {@code INACTIVE},
 * {@code ARCHIVED}) — all three are permitted by the {@code chk_subjects_status}
 * CHECK constraint (V6). An unknown value fails to deserialize and yields 400.
 */
public record UpdateSubjectStatusRequest(@NotNull AcademicStatus status) {}
