package com.quizopia.backend.academic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/subjects} (SUBJECT_CREATE). The caller
 * (ACADEMIC_ADMIN) supplies the target {@code schoolId} + {@code gradeLevelId};
 * the backend validates that the school exists, the grade level belongs to that
 * school, and the code is unique within {@code (school, gradeLevel)}.
 */
public record CreateSubjectRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 2000) String description,
        @NotNull Long schoolId,
        @NotNull Long gradeLevelId
) {}
