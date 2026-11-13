package com.quizopia.backend.exam.dto;

/**
 * Monitoring policy projection returned inside {@code ExamSessionDetailResponse}
 * (teachers) and {@code SessionEligibilityResponse} (authorized students,
 * issue #80 acceptance: "Policy notice and feature flags exposed to authorized
 * student only").
 *
 * <p>{@code cameraRequired} is the DERIVED enforced property of
 * {@code monitoringEnabled} (ADR-008 invariant 2) — never client-settable.
 * {@code configurationFrozen} is likewise derived at read time from the same
 * conditions that gate writes (status not DRAFT/SCHEDULED, scheduled start
 * reached, or any attempt exists), so it can never contradict the write path.
 *
 * <p>Retention days are surfaced for transparency (ADR-008 D7 defaults);
 * they are not teacher-configurable in P4-A.
 */
public record MonitoringPolicyResponse(
        boolean monitoringEnabled,
        boolean cameraRequired,
        boolean aiDetectionEnabled,
        boolean activityTrackingEnabled,
        boolean evidenceSnapshotsEnabled,
        boolean fullRecordingEnabled,
        boolean evidenceClipsEnabled,
        boolean configurationFrozen,
        int mediaRetentionDays,
        int activityRetentionDays) {

    /** The all-OFF policy projection for legacy sessions (no policy row). */
    public static MonitoringPolicyResponse off() {
        return new MonitoringPolicyResponse(false, false, false, false, false, false, false,
                false, 30, 90);
    }
}
