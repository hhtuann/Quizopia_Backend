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
        /** Body value used only by the canonical POST /api/exam-sessions; the nested
         * POST /api/exams/{examId}/sessions route supplies it from the path instead. */
        @Positive Long examId,
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
        List<Long> classroomIds,
        /**
         * Monitoring configuration (P4-A1, issue #80). Null or absent = all
         * OFF (legacy payloads unchanged). cameraRequired is NOT accepted
         * here — it is derived server-side from monitoringEnabled.
         */
        MonitoringPolicyRequest monitoring
) {

    /** Legacy 9-arg shape (pre-P4-A callers): monitoring stays absent (all OFF). */
    public CreateExamSessionRequest(Long examId, String code, String title, Instant startsAt,
            Instant endsAt, Integer maxAttempts, Integer durationMinutes,
            SessionVisibility visibility, List<Long> classroomIds) {
        this(examId, code, title, startsAt, endsAt, maxAttempts, durationMinutes, visibility,
                classroomIds, null);
    }
}
