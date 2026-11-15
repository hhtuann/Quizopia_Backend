package com.quizopia.backend.evidence.domain;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the V40 {@code monitoring_evidence} table — snapshots
 * and clips with fixed provenance (P4-D, issues #91/#93, ADR-009 §1/§2).
 *
 * <p>Lifecycle: {@code PENDING} → {@code AVAILABLE}/{@code PARTIAL}/
 * {@code FAILED}, and {@code EXPIRED} after retention. {@code AVAILABLE} /
 * {@code PARTIAL} imply a real stored object (HEAD-able); {@code FAILED}
 * carries an honest machine reason in {@code error}; {@code EXPIRED} keeps
 * metadata only — the API must never hand out a dead URL.
 *
 * <p>Provenance is fixed at insert and never rewritten:
 * {@code CLIENT_CAPTURE} (student browser JPEG), {@code FULL_RECORDING} /
 * {@code ROLLING_BUFFER} (egress-written MP4 material).
 */
@Entity
@Table(name = "monitoring_evidence")
public class MonitoringEvidence {

    public static final String KIND_SNAPSHOT = "SNAPSHOT";
    public static final String KIND_CLIP = "CLIP";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final String PROVENANCE_CLIENT_CAPTURE = "CLIENT_CAPTURE";
    public static final String PROVENANCE_FULL_RECORDING = "FULL_RECORDING";
    public static final String PROVENANCE_ROLLING_BUFFER = "ROLLING_BUFFER";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "alert_id")
    private Long alertId;

    @Column(name = "binding_id")
    private UUID bindingId;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "provenance", nullable = false, length = 16)
    private String provenance;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "object_key", length = 256)
    private String objectKey;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256")
    private String sha256;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    /** Clip coverage/gap report (JSON) — honest PARTIAL metadata. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coverage_json", columnDefinition = "jsonb")
    private tools.jackson.databind.JsonNode coverageJson;

    @Column(name = "error")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected MonitoringEvidence() {
        // JPA
    }

    public static MonitoringEvidence newSnapshot(UUID id, Long examSessionId, Long attemptId,
            Long alertId, String idempotencyKey, Instant capturedAt, Instant expiresAt,
            Instant now) {
        MonitoringEvidence evidence = base(id, examSessionId, attemptId, alertId,
                KIND_SNAPSHOT, PROVENANCE_CLIENT_CAPTURE, now, expiresAt);
        evidence.idempotencyKey = idempotencyKey;
        evidence.capturedAt = capturedAt;
        evidence.contentType = "image/jpeg";
        return evidence;
    }

    public static MonitoringEvidence newClip(UUID id, Long examSessionId, Long attemptId,
            Long alertId, String provenance, Instant windowStart, Instant windowEnd,
            Instant expiresAt, Instant now) {
        MonitoringEvidence evidence = base(id, examSessionId, attemptId, alertId,
                KIND_CLIP, provenance, now, expiresAt);
        evidence.windowStart = windowStart;
        evidence.windowEnd = windowEnd;
        evidence.contentType = "video/mp4";
        return evidence;
    }

    private static MonitoringEvidence base(UUID id, Long examSessionId, Long attemptId,
            Long alertId, String kind, String provenance, Instant now, Instant expiresAt) {
        MonitoringEvidence evidence = new MonitoringEvidence();
        evidence.id = id;
        evidence.examSessionId = examSessionId;
        evidence.attemptId = attemptId;
        evidence.alertId = alertId;
        evidence.kind = kind;
        evidence.status = STATUS_PENDING;
        evidence.provenance = provenance;
        evidence.createdAt = now;
        evidence.updatedAt = now;
        evidence.expiresAt = expiresAt;
        return evidence;
    }

    public void markAvailable(String objectKey, String sha256, long bytes,
            Integer width, Integer height) {
        this.status = STATUS_AVAILABLE;
        this.objectKey = objectKey;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.error = null;
        touch();
    }

    public void markPartial(String objectKey, String sha256, long bytes,
            tools.jackson.databind.JsonNode coverageJson) {
        this.status = STATUS_PARTIAL;
        this.objectKey = objectKey;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.coverageJson = coverageJson;
        this.error = null;
        touch();
    }

    public void markFailed(String reason) {
        this.status = STATUS_FAILED;
        this.error = reason;
        touch();
    }

    public void markExpired() {
        this.status = STATUS_EXPIRED;
        touch();
    }

    private void touch() {
        // updatedAt maintained by callers that hold the entity
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

    public Long getAlertId() {
        return alertId;
    }

    public String getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public String getProvenance() {
        return provenance;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getSha256() {
        return sha256;
    }

    public Long getBytes() {
        return bytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public String getCoverageJson() {
        return coverageJson == null ? null : coverageJson.toString();
    }

    tools.jackson.databind.JsonNode coverageNode() {
        return coverageJson;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
