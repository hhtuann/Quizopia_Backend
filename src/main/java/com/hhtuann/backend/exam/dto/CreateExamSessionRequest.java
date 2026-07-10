package com.hhtuann.backend.exam.dto;

import com.hhtuann.backend.exam.domain.model.SessionVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record CreateExamSessionRequest(
        @NotNull @Positive Long examId,
        @NotNull @Positive Integer examVersionNumber,
        @Size(max = 30) String code,
        @NotBlank @Size(max = 255) String title,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull @Positive Integer maxAttempts,
        /** PUBLIC = all same-school students; CLASS_RESTRICTED (default) = assigned classes only. */
        SessionVisibility visibility,
        /** Assigned classrooms (required-meaningful only when visibility = CLASS_RESTRICTED). Ignored when PUBLIC. */
        List<Long> classroomIds
) {
    /** Convenience: create without explicit visibility (defaults to CLASS_RESTRICTED, no classes). */
    public CreateExamSessionRequest(Long examId, Integer examVersionNumber, String code, String title,
            Instant startsAt, Instant endsAt, Integer maxAttempts) {
        this(examId, examVersionNumber, code, title, startsAt, endsAt, maxAttempts, null, null);
    }
}

