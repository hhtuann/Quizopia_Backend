package com.quizopia.backend.authentication.dto;

import com.quizopia.backend.identity.domain.model.UserStatus;

import java.util.List;

/**
 * Registration response. {@code phone} echoes the plaintext value the caller
 * just submitted; the stored ciphertext, password hash, token version and any
 * token are deliberately never returned.
 */
public record RegisterResponse(
        Long id,
        String username,
        String email,
        String displayName,
        String phone,
        UserStatus status,
        List<String> roles
) {
}
