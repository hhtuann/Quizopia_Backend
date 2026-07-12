package com.quizopia.backend.user.dto;

import java.time.Instant;
import java.util.List;

/**
 * User row in the admin list. Carries only non-sensitive account metadata.
 * <strong>Never</strong> includes the password hash, encrypted phone/national-id,
 * token version, or refresh tokens.
 *
 * @param status the {@link com.quizopia.backend.identity.domain.model.UserStatus}
 *               enum name (e.g. {@code "ACTIVE"})
 * @param roles  the codes of the user's currently-effective roles
 */
public record UserListItem(
        Long id,
        String username,
        String email,
        String displayName,
        String status,
        List<String> roles,
        Instant createdAt
) {}
