package com.hhtuann.backend.authentication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public registration request.
 *
 * <p>{@code accountType} is optional: a {@code null} value means STUDENT.
 * {@code teacherInviteCode} is required only when {@code accountType} is
 * TEACHER (enforced by the service). No complex phone regex is
 * applied beyond presence; the service trims surrounding whitespace from the
 * identifier and display fields. The password is never trimmed.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 150) String displayName,
        @NotBlank String phone,
        AccountType accountType,
        String teacherInviteCode
) {
}
