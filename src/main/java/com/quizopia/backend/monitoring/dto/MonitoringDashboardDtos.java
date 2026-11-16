package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * P4-E unified dashboard read DTOs (issue #95, ADR-010 §2.3 / design §3.1–3.2).
 *
 * <p>Signals are kept SEPARATE by design: attempt status, camera state, AI
 * counters, recording health and evidence health each carry only what their
 * own subsystem persisted — a missing signal is honest absence ("NONE"),
 * never an inferred verdict (issue #95 guardrail).
 */
public final class MonitoringDashboardDtos {

    private MonitoringDashboardDtos() {
    }

    /** {@code GET /api/exam-sessions/{id}/monitoring/overview}. */
    public record MonitoringOverviewResponse(
            Long sessionId,
            Instant asOf,
            boolean monitoringEnabled,
            AttemptsOverview attempts,
            CameraOverview camera,
            AiOverview ai,
            RecordingOverview recording,
            EvidenceOverview evidence) {

        public record AttemptsOverview(int total, int inProgress, int submitted, int graded) {
        }

        public record CameraOverview(
                boolean policyRequired,
                int connected,
                int reconnecting,
                int idle,
                int revoked) {
        }

        public record AiOverview(
                boolean enabled,
                int openAlerts,
                int closedAlerts,
                int unreviewedAlerts,
                @Nullable Instant lastDetectionAt) {
        }

        public record RecordingOverview(
                boolean fullRecordingEnabled,
                boolean rollingBufferActive,
                int activeJobs,
                int completeJobs,
                int failedJobs) {
        }

        public record EvidenceOverview(
                int available,
                int partial,
                int pending,
                int failed,
                int expired) {
        }
    }

    /** {@code GET /api/exam-sessions/{id}/monitoring/participants}. */
    public record MonitoringParticipantsResponse(
            Long sessionId,
            List<Item> items,
            @Nullable String nextCursor) {

        public record Item(
                Long attemptId,
                Long userId,
                String displayName,
                int attemptNumber,
                String attemptStatus,
                Instant startedAt,
                @Nullable Instant deadlineAt,
                @Nullable Instant submittedAt,
                @Nullable Instant lastActivityAt,
                CameraState camera,
                AiState ai,
                RecordingState recording,
                EvidenceState evidence,
                /** Aggregate of the attempt's alerts — UNREVIEWED when any alert is. */
                String reviewStatus) {

            /** cameraState values: CONNECTED | RECONNECTING | IDLE | REVOKED | NONE. */
            public record CameraState(
                    String state,
                    @Nullable Instant connectedSince,
                    @Nullable Instant lastVerifiedAt,
                    @Nullable String mediaIdentity) {
            }

            public record AiState(
                    @Nullable Instant lastDetectionAt,
                    int openAlerts,
                    int totalAlerts,
                    int unreviewedAlerts) {
            }

            public record RecordingState(
                    /** NONE | FULL | ROLLING — policy mode, per design §3.2. */
                    String mode,
                    int activeJobs,
                    int completeJobs,
                    int failedJobs) {
            }

            public record EvidenceState(int snapshots, int clips) {
            }
        }
    }
}
