package com.quizopia.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotNull @Size(min = 1, max = 128) String currentPassword,
        @NotNull @Size(min = 8, max = 128) String newPassword,
        @NotNull @Size(min = 8, max = 128) String confirmPassword) {
    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported credential field");
    }

    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
