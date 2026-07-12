package com.quizopia.backend.user.dto;

import com.quizopia.backend.authentication.dto.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/users} (USER_CREATE). The SYSTEM_ADMIN
 * supplies credentials + the foundational account type (STUDENT/TEACHER); the
 * backend hashes the password (Argon2id), encrypts phone (AES-256-GCM),
 * persists the user + role assignment, and (in demo mode) the matching academic
 * profile. Other roles (ACADEMIC_ADMIN/SYSTEM_ADMIN) are granted later via
 * {@link AssignRoleRequest}.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 150) String displayName,
        @NotNull AccountType accountType,
        @Size(max = 20) String phone
) {}
