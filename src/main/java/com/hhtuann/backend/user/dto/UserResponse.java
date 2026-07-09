package com.hhtuann.backend.user.dto;

import java.time.Instant;
import java.util.List;

/**
 * Single-user detail payload. {@link UserListItem} plus the lifecycle timestamps
 * ({@code lockedUntil}, {@code lastLoginAt}). Still no sensitive fields.
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String status,
        List<String> roles,
        Instant createdAt,
        Instant lockedUntil,
        Instant lastLoginAt
) {}
