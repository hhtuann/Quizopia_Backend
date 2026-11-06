package com.quizopia.backend.account.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Full replacement of the owner's editable projection; omitted phone clears it. */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 150) String displayName,
        @Pattern(regexp = "\\+?[0-9]{7,15}") String phone
) {
    public UpdateProfileRequest {
        if (displayName != null) {
            if (displayName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid profile display name");
            }
            displayName = displayName.trim();
        }
        if (phone != null) {
            phone = phone.trim();
            if (phone.isBlank()) {
                phone = null;
            }
        }
    }

    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        // Do not echo attacker-controlled names or values into exception/log text.
        throw new IllegalArgumentException("Unsupported profile field");
    }
}
