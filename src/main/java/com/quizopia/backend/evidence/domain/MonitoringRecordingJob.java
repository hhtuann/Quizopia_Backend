package com.quizopia.backend.evidence.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the V40 {@code monitoring_recording_jobs} table — one
 * row per LiveKit egress run (P4-D, issues #92/#93, ADR-009 §2.2).
 *
 * <p>{@code kind = FULL} rows are continuous per-camera-connection segments;
 * {@code kind = ROLLING} rows are bounded pulses of the rolling buffer
 * (recording OFF + clips ON). Status and file metadata mirror the
 * authoritative egress {@code EgressInfo}; they are never inferred from the
 * absence of signals — a run that never reached {@code COMPLETE} with a file
 * result is {@code FAILED}/{@code ABORTED}, never a success.
 *
 * <p>Coverage for clip extraction is computed from {@code started_at}/
 * {@code ended_at} of the rows that own an object. Rows outlive their
 * objects: rolling pulse objects are deleted while the row stays as gap
 * evidence ({@code object_deleted = true}).
 */
@Entity
@Table(name = "monitoring_recording_jobs")
public class MonitoringRecordingJob {

    public static final String KIND_FULL = "FULL";
    public static final String KIND_ROLLING = "ROLLING";

    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDING = "ENDING";
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ABORTED = "ABORTED";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "binding_id", nullable = false)
    private UUID bindingId;

    /** FULL (continuous segment) or ROLLING (bounded pulse). */
    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "egress_id", nullable = false, unique = true, length = 64)
    private String egressId;

    @Column(name = "room_name", nullable = false, length = 64)
    private String roomName;

    @Column(name = "track_sid", nullable = false, length = 64)
    private String trackSid;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "pulse_index", nullable = false)
    private int pulseIndex;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "object_key", length = 256)
    private String objectKey;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error")
    private String error;

    @Column(name = "object_deleted", nullable = false)
    private boolean objectDeleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoringRecordingJob() {
        // JPA
    }

    public static MonitoringRecordingJob newJob(UUID id, Long examSessionId, Long attemptId,
            UUID bindingId, String kind, String egressId, String roomName, String trackSid,
            int pulseIndex, Instant now) {
        MonitoringRecordingJob job = new MonitoringRecordingJob();
        job.id = id;
        job.examSessionId = examSessionId;
        job.attemptId = attemptId;
        job.bindingId = bindingId;
        job.kind = kind;
        job.egressId = egressId;
        job.roomName = roomName;
        job.trackSid = trackSid;
        job.status = STATUS_STARTING;
        job.pulseIndex = pulseIndex;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public UUID getBindingId() {
        return bindingId;
    }

    public String getKind() {
        return kind;
    }

    public String getEgressId() {
        return egressId;
    }

    public String getRoomName() {
        return roomName;
    }

    public String getTrackSid() {
        return trackSid;
    }

    public String getStatus() {
        return status;
    }

    public int getPulseIndex() {
        return pulseIndex;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public Long getBytes() {
        return bytes;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getError() {
        return error;
    }

    public boolean isObjectDeleted() {
        return objectDeleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
