package com.quizopia.backend.exam.dto;

/**
 * Teacher-supplied monitoring configuration for session create/update
 * (P4-A1, issue #80). All flags default to FALSE; {@code null} (legacy
 * payloads) means "no monitoring" — existing clients are unaffected.
 *
 * <p>{@code cameraRequired} is NOT a request field: it is derived from
 * {@code monitoringEnabled} server-side (ADR-008 invariant 2) and cannot be
 * waived or asserted by the client.
 *
 * <p>Valid combinations (server-enforced, see
 * {@code SessionMonitoringPolicyValidator}):
 * <ul>
 *   <li>monitoring only for {@code CLASS_RESTRICTED} sessions with at least
 *       one assigned ACTIVE teacher-owned classroom;</li>
 *   <li>every feature flag requires {@code monitoringEnabled};</li>
 *   <li>{@code evidenceSnapshotsEnabled} additionally requires
 *       {@code aiDetectionEnabled};</li>
 *   <li>{@code evidenceClipsEnabled} additionally requires
 *       {@code fullRecordingEnabled} (no rolling-buffer capability in P4-A).</li>
 * </ul>
 *
 * @param monitoringEnabled        master switch; OFF by default
 * @param aiDetectionEnabled       AI-assisted detection (P4-C realizes it)
 * @param activityTrackingEnabled  durable activity tracking (P4-A2)
 * @param evidenceSnapshotsEnabled still-frame evidence (requires AI detection)
 * @param fullRecordingEnabled     full video recording; OFF by default (D4)
 * @param evidenceClipsEnabled     clip extraction (requires full recording)
 */
public record MonitoringPolicyRequest(
        Boolean monitoringEnabled,
        Boolean aiDetectionEnabled,
        Boolean activityTrackingEnabled,
        Boolean evidenceSnapshotsEnabled,
        Boolean fullRecordingEnabled,
        Boolean evidenceClipsEnabled) {

    /** Normalizes a possibly-null request to explicit booleans (legacy-safe). */
    public boolean monitoringEnabledOrDefault() {
        return monitoringEnabled != null && monitoringEnabled;
    }

    public boolean aiDetectionOrDefault() {
        return aiDetectionEnabled != null && aiDetectionEnabled;
    }

    public boolean activityTrackingOrDefault() {
        return activityTrackingEnabled != null && activityTrackingEnabled;
    }

    public boolean evidenceSnapshotsOrDefault() {
        return evidenceSnapshotsEnabled != null && evidenceSnapshotsEnabled;
    }

    public boolean fullRecordingOrDefault() {
        return fullRecordingEnabled != null && fullRecordingEnabled;
    }

    public boolean evidenceClipsOrDefault() {
        return evidenceClipsEnabled != null && evidenceClipsEnabled;
    }

    /** True when the request carries no monitoring block at all (legacy payload). */
    public boolean isAbsent() {
        return monitoringEnabled == null && aiDetectionEnabled == null
                && activityTrackingEnabled == null && evidenceSnapshotsEnabled == null
                && fullRecordingEnabled == null && evidenceClipsEnabled == null;
    }
}
