package com.quizopia.backend.exam.dto;

import com.quizopia.backend.exam.domain.model.SessionVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /api/exams/{examId}/sessions} (and the legacy
 * {@code POST /api/exam-sessions}). The exam must be PUBLISHED. There is NO
 * version selection — one exam has exactly one published content set.
 */
public record CreateExamSessionRequest(
        @NotNull @Positive Long examId,
        @Size(max = 30) String code,
        @NotBlank @Size(max = 255) String title,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotNull @jakarta.validation.constraints.Min(0) Integer maxAttempts,
        /**
         * Session-owned duration in minutes. NULL = prefill from the exam's
         * editor-metadata default. 0 = unlimited.
         */
        Integer durationMinutes,
        /** PUBLIC = platform users; CLASS_RESTRICTED (default) = assigned classes only. */
        SessionVisibility visibility,
        /** Assigned classrooms (meaningful only when visibility = CLASS_RESTRICTED). */
        List<Long> classroomIds
) {
}
