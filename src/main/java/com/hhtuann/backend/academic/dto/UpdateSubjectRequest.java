package com.hhtuann.backend.academic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/subjects/{id}} (SUBJECT_UPDATE). Only the
 * mutable non-status fields are editable: {@code name} (required) and an
 * optional {@code description}. Code, school and grade level are immutable
 * after creation; status changes use {@link UpdateSubjectStatusRequest}.
 */
public record UpdateSubjectRequest(
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description
) {}
