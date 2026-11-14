package com.quizopia.backend.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record UpdateExamSessionRequest(
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 255) String title,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull @Positive Integer maxAttempts,
        /**
         * Monitoring configuration (P4-A1, issue #80). NULL/absent = NO CHANGE
         * (legacy title-only edits keep working on frozen sessions); a
         * non-null block REPLACES the whole configuration and is rejected with
         * 409 EXAM_SESSION_MONITORING_FROZEN once the session has opened, its
         * scheduled start has been reached, or any attempt exists.
         */
        MonitoringPolicyRequest monitoring
) {

    /** Legacy 5-arg shape (pre-P4-A callers): monitoring = no change. */
    public UpdateExamSessionRequest(Integer expectedVersion, String title, Instant startsAt,
            Instant endsAt, Integer maxAttempts) {
        this(expectedVersion, title, startsAt, endsAt, maxAttempts, null);
    }
}
