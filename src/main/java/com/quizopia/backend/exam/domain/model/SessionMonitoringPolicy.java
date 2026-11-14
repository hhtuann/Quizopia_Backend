package com.quizopia.backend.exam.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * JPA entity mapping the V33 {@code session_monitoring_policies} table — the
 * per-session Exam Monitoring policy (P4-A1, issue #80).
 *
 * <p><b>Ownership:</b> 1:1 with {@code exam_sessions} (UNIQUE
 * {@code exam_session_id}, FK CASCADE). Sessions created before P4-A have NO
 * row here; the read path treats a missing row as the all-OFF legacy policy so
 * existing sessions are unchanged.
 *
 * <p><b>No derived columns:</b> {@code cameraRequired} is NOT persisted — it
 * is a derived enforced property of {@code monitoringEnabled} (ADR-008
 * invariant 2), so a contradictory configuration cannot exist in the DB.
 * Likewise the configuration freeze is derived at write time from
 * (status, startsAt, attempt existence) under the session row lock, never
 * persisted (ADR-008 invariant 3).
 *
 * <p><b>Valid combinations</b> (validated by
 * {@code ExamSessionService}/{@code SessionMonitoringPolicyValidator} on every
 * write): every feature flag requires {@code monitoringEnabled};
 * {@code evidenceSnapshotsEnabled} additionally requires
 * {@code aiDetectionEnabled}; {@code evidenceClipsEnabled} additionally
 * requires {@code fullRecordingEnabled} (no rolling-buffer capability exists
 * in P4-A; P4-D extends).
 *
 * <p>{@code mediaRetentionPolicyDays} (30) and
 * {@code activityRetentionPolicyDays} (90) are the ADR-008 D7 defaults; they
 * are the per-session source of truth for the future P4-D cleanup jobs and are
 * not teacher-configurable in P4-A.
 */
@Entity
@Table(name = "session_monitoring_policies")
public class SessionMonitoringPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "exam_session_id", nullable = false, unique = true)
    private Long examSessionId;

    @Column(name = "monitoring_enabled", nullable = false)
    private boolean monitoringEnabled = false;

    @Column(name = "ai_detection_enabled", nullable = false)
    private boolean aiDetectionEnabled = false;

    @Column(name = "activity_tracking_enabled", nullable = false)
    private boolean activityTrackingEnabled = false;

    @Column(name = "evidence_snapshots_enabled", nullable = false)
    private boolean evidenceSnapshotsEnabled = false;

    @Column(name = "full_recording_enabled", nullable = false)
    private boolean fullRecordingEnabled = false;

    @Column(name = "evidence_clips_enabled", nullable = false)
    private boolean evidenceClipsEnabled = false;

    @Column(name = "media_retention_days", nullable = false)
    private int mediaRetentionDays = 30;

    @Column(name = "activity_retention_days", nullable = false)
    private int activityRetentionDays = 90;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SessionMonitoringPolicy() {
        // JPA
    }

    /**
     * Creates an all-OFF policy for a session (the default for every new
     * session, including legacy create payloads that carry no monitoring
     * block).
     */
    public static SessionMonitoringPolicy offFor(Long examSessionId) {
        SessionMonitoringPolicy policy = new SessionMonitoringPolicy();
        policy.examSessionId = Objects.requireNonNull(examSessionId, "examSessionId");
        return policy;
    }

    /** Applies a validated configuration to this policy. */
    public void configure(boolean monitoringEnabled,
            boolean aiDetectionEnabled,
            boolean activityTrackingEnabled,
            boolean evidenceSnapshotsEnabled,
            boolean fullRecordingEnabled,
            boolean evidenceClipsEnabled) {
        this.monitoringEnabled = monitoringEnabled;
        this.aiDetectionEnabled = aiDetectionEnabled;
        this.activityTrackingEnabled = activityTrackingEnabled;
        this.evidenceSnapshotsEnabled = evidenceSnapshotsEnabled;
        this.fullRecordingEnabled = fullRecordingEnabled;
        this.evidenceClipsEnabled = evidenceClipsEnabled;
    }

    public Long getId() {
        return id;
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    public boolean isAiDetectionEnabled() {
        return aiDetectionEnabled;
    }

    public boolean isActivityTrackingEnabled() {
        return activityTrackingEnabled;
    }

    public boolean isEvidenceSnapshotsEnabled() {
        return evidenceSnapshotsEnabled;
    }

    public boolean isFullRecordingEnabled() {
        return fullRecordingEnabled;
    }

    public boolean isEvidenceClipsEnabled() {
        return evidenceClipsEnabled;
    }

    public int getMediaRetentionDays() {
        return mediaRetentionDays;
    }

    public int getActivityRetentionDays() {
        return activityRetentionDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Derived enforced property (ADR-008 invariant 2) — never stored. */
    public boolean isCameraRequired() {
        return monitoringEnabled;
    }
}
