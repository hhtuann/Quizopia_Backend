package com.quizopia.backend.user.dto;

/**
 * Role catalog entry returned by {@code GET /api/roles} (ROLE_READ).
 */
public record RoleResponse(
        Long id,
        String code,
        String name,
        String description
) {}
