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
                /** Open transport connection (presence), per user. */
                int connected,
                /** Connected AND a verified published track (F04). */
                int publishing,
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

    /**
     * {@code GET /api/exam-sessions/{id}/monitoring/participants}.
     *
     * @param teacherSubscriptionState honestly {@code NOT_AVAILABLE}: the
     *     backend cannot observe which camera the TEACHER's browser is
     *     subscribed to (LiveKit subscribe state is client-side; the
     *     backend never fabricates it — F04). The teacher UI derives this
     *     from its own room handle.
     */
    public record MonitoringParticipantsResponse(
            Long sessionId,
            List<Item> items,
            @Nullable String nextCursor,
            String teacherSubscriptionState) {

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
                    @Nullable String mediaIdentity,
                    /** F04: verified published track on the open connection. */
                    boolean publishing,
                    /** F04: transport presence (open connection). */
                    boolean online) {
            }

            public record AiState(
                    /** Session policy flag — false ⇒ the whole AI block is DISABLED. */
                    boolean enabled,
                    @Nullable Instant lastDetectionAt,
                    int openAlerts,
                    int totalAlerts,
                    int unreviewedAlerts,
                    /** F04: raw accepted detections — separate from alerts. */
                    long detections,
                    /** F04: quarantined (late/dup) — an honest DEGRADED signal. */
                    long quarantined) {
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
