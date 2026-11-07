package com.quizopia.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Forgot-password intake body (ADR-004). Syntax is the ONLY thing validated
 * here — syntax errors are 400 independent of account existence; every
 * syntactically valid address gets the identical generic 202.
 */
public record PasswordForgotRequest(
        @NotBlank @Email @Size(max = 254) String email) {

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported field");
    }

    @Override
    public String toString() {
        return "PasswordForgotRequest[redacted]";
    }
}
