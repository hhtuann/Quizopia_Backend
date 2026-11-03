package com.quizopia.backend.exam.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Replaces display metadata only. Null/omitted description clears it. */
public record UpdateExamMetadataRequest(
        @NotNull @PositiveOrZero Integer expectedExamVersion,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description
) {
    // Fail closed for content, grading, ownership and misspelled fields even
    // when the application's mapper ignores unknown properties by default.
    @JsonAnySetter
    public void rejectUnsupportedField(String name, Object value) {
        throw new IllegalArgumentException("Unsupported exam metadata field: " + name);
    }
}
