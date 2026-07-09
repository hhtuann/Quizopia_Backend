package com.hhtuann.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/users/{id}/roles} (USER_ROLE_ASSIGN). The
 * role code must be one of the four foundational roles seeded in V3
 * (SYSTEM_ADMIN, ACADEMIC_ADMIN, TEACHER, STUDENT); assignment is idempotent.
 */
public record AssignRoleRequest(@NotBlank String roleCode) {}
