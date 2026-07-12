package com.quizopia.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/users/{id}} (USER_UPDATE). Both fields are
 * optional: a {@code null} field means "no change". Email uniqueness is
 * re-checked only when a new email is supplied.
 */
public record UpdateUserRequest(
        @Size(max = 150) String displayName,
        @Email @Size(max = 254) String email
) {}
