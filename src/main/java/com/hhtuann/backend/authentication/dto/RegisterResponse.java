package com.hhtuann.backend.authentication.dto;

import com.hhtuann.backend.identity.domain.model.UserStatus;

import java.util.List;

/**
 * Registration response. {@code phone} and {@code nationalId} echo the plaintext
 * values the caller just submitted; the stored ciphertext, password hash, token
 * version and any token are deliberately never returned.
 */
public record RegisterResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String phone,
        String nationalId,
        UserStatus status,
        List<String> roles
) {
}
