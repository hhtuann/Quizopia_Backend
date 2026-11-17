package com.quizopia.backend.monitoring.dto;

import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * P4-E per-attempt monitoring report (issue #96, ADR-010 §2.4 / design §3.6).
 *
 * <p>Read-time aggregation of persisted P4-A..D rows only. Counts keep the
 * three layers strictly separate — raw detections / grouped alerts /
 * reviewed alerts — so nothing double-counts. No field joins or reads from
 * {@code grades}/{@code grade_items}; the fixed disclaimer states that
 * observations are never a verdict. Playable media carries the P4-D mint
 * endpoint REFERENCE (never a presigned URL — every mint stays audited).
 */
public record AttemptMonitoringReportResponse(
        AttemptMeta attempt,
        Policy policy,
        MonitoringWindow monitoringWindow,
        ActivitySummary activity,
        CameraReport camera,
        RecordingReport recording,
        AiReport ai,
        List<EvidenceRow> evidence,
        /** F11: honest completeness — totals and truncation flags. */
        long evidenceTotal,
        boolean evidenceTruncated,
        List<ReviewRow> reviews,
        long reviewsTotal,
        boolean reviewsTruncated,
        Retention retention,
        String disclaimer) {

    public record AttemptMeta(
            Long attemptId,
            Long sessionId,
            String sessionTitle,
            String sessionCode,
            Long studentUserId,
            String studentName,
            int attemptNumber,
            String attemptStatus,
            Instant startedAt,
            @Nullable Instant submittedAt,
            @Nullable Instant deadlineAt) {
    }

    public record Policy(
            boolean monitoringEnabled,
            boolean aiDetectionEnabled,
            boolean activityTrackingEnabled,
            boolean snapshotsEnabled,
            boolean fullRecordingEnabled,
            boolean clipsEnabled,
            int mediaRetentionDays,
            int activityRetentionDays) {
    }

    public record MonitoringWindow(Instant from, Instant to, long minutes) {
    }

    public record ActivitySummary(
            long totalEvents,
            long answerAccepted,
            long distinctQuestionsAnswered,
            BrowserSummary browser,
            List<CountByType> byType) {

        public record BrowserSummary(
                long tabHidden,
                long tabVisible,
                long focusLost,
                long focusGained,
                long pageReload,
                long connectionLost) {
        }

        public record CountByType(String type, long count) {
        }
    }

    public record CameraReport(
            boolean neverConnected,
            List<Period> connectedPeriods,
            List<Period> disconnectPeriods,
            long monitoringGaps,
            @Nullable String lastEvent) {

        public record Period(Instant from, @Nullable Instant to) {
        }
    }

    public record RecordingReport(
            String mode,
            List<Segment> segments,
            List<Gap> gaps,
            double coverageRatio) {

        public record Segment(
                String jobId,
                String kind,
                String status,
                Instant startedAt,
                @Nullable Instant endedAt,
                long bytes,
                long durationMs,
                boolean objectDeleted,
                @Nullable Instant expiresAt,
                @Nullable String playbackRef) {
        }

        public record Gap(Instant from, Instant to, double seconds) {
        }
    }

    public record AiReport(
            long rawDetections,
            long quarantined,
            long groupedAlerts,
            long openAlerts,
            long closedAlerts,
            long reviewedAlerts,
            long unreviewedAlerts,
            long falsePositives,
            List<ModelRow> models,
            String limitsNote) {

        public record ModelRow(
                String modelId,
                String modelVersion,
                String provenance,
                @Nullable String runtime,
                long detections) {
        }
    }

    public record EvidenceRow(
            String evidenceId,
            String kind,
            String status,
            String provenance,
            @Nullable Instant capturedAt,
            @Nullable Instant windowStart,
            @Nullable Instant windowEnd,
            long bytes,
            @Nullable String sha256,
            @Nullable Instant expiresAt,
            @Nullable Long alertId,
            /** P4-D mint endpoint; present only for playable states. */
            @Nullable String playbackRef) {
    }

    public record ReviewRow(
            long reviewId,
            Long alertId,
            String action,
            @Nullable String note,
            String reviewerName,
            Instant reviewedAt,
            int version) {
    }

    public record Retention(int mediaRetentionDays, int activityRetentionDays, Instant asOf) {
    }
}
