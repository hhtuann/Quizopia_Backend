package com.quizopia.backend.authentication.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public registration request (Identity-M1).
 *
 * <p>Every account is STUDENT: a {@code null} {@code accountType} means
 * STUDENT, and any other value is rejected by the service
 * ({@code AUTH_TEACHER_REGISTRATION_DISABLED}) before any write. The historic
 * {@code teacherInviteCode} field is gone; unknown properties sent by older
 * clients are ignored by Jackson. No complex phone regex is applied beyond
 * presence; the service trims surrounding whitespace from the identifier and
 * display fields. The password is never trimmed.
 */
public record RegisterRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 150) String displayName,
        @NotBlank String phone,
        AccountType accountType
) {
}
