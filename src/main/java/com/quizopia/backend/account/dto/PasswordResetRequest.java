package com.quizopia.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Reset-password body (ADR-004): the opaque token from the mail link's
 * fragment plus the new password with its confirmation. Passwords are
 * untrimmed 8–128 characters; every token problem (invalid, expired,
 * replaced, replayed) answers with the single shared 400 code.
 */
public record PasswordResetRequest(
        @NotBlank @Size(max = 256) String token,
        @NotNull @Size(min = 8, max = 128) String newPassword,
        @NotNull @Size(min = 8, max = 128) String confirmPassword) {

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported field");
    }

    @Override
    public String toString() {
        return "PasswordResetRequest[redacted]";
    }
}
