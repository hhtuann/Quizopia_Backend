package com.hhtuann.backend.authentication.dto;

import com.hhtuann.backend.identity.domain.model.UserStatus;

import java.util.List;

/**
 * Current-user response for {@code GET /api/auth/me}. {@code phone} and
 * {@code nationalId} are decrypted only because the caller is the owner; no
 * ciphertext, password hash, token version, lockout or session fields are
 * exposed.
 */
public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String phone,
        String nationalId,
        UserStatus status,
        List<String> roles,
        List<String> permissions
) {
}
