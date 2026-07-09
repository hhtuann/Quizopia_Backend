package com.hhtuann.backend.user.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/roles} — the four foundational roles.
 */
public record RoleListResponse(List<RoleResponse> items) {}
