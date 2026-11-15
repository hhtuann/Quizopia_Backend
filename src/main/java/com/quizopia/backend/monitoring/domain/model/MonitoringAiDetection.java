package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * JPA entity mapping the V38 {@code monitoring_ai_detections} table — the
 * durable detection ledger of both ingest paths (P4-C, CONTRACT §3/§4).
 *
 * <p><b>Observations, never verdicts (ADR-008 D8):</b> a row records what a
 * model saw in one frame window (browser ORT-Web or the future worker).
 * Nothing in this table feeds grading; there is no FK or write path to
 * {@code grades}/{@code grade_items} by design.
 *
 * <p><b>Idempotency:</b> {@code eventId} is the client- (or worker-)
 * supplied UUID with a UNIQUE constraint; the ingest path inserts with
 * {@code ON CONFLICT (event_id) DO NOTHING} so retried batches are exact
 * no-ops and can never re-aggregate.
 *
 * <p><b>Quarantine (CONTRACT §3.2):</b> late or out-of-window rows are
 * STORED with a reason (they bind the original attempt via attemptId) but
 * flagged {@code quarantined}; the aggregation service never reads them.
 * The entity is used for reads/validation; inserts go through the
 * repository native statement.
 */
@Entity
@Table(name = "monitoring_ai_detections")
public class MonitoringAiDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "media_binding_id")
    private UUID mediaBindingId;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 20)
    private AiProvenance provenance;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AiDetectionType eventType;

    @Column(name = "model_id", nullable = false, length = 40)
    private String modelId;

    @Column(name = "model_version", nullable = false, length = 40)
    private String modelVersion;

    @Column(name = "runtime", nullable = false, length = 20)
    private String runtime;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    @Column(name = "quarantined", nullable = false)
    private boolean quarantined;

    @Column(name = "quarantine_reason", length = 40)
    private String quarantineReason;

    @Column(name = "frame_width", nullable = false)
    private int frameWidth;

    @Column(name = "frame_height", nullable = false)
    private int frameHeight;

    @Column(name = "processing_ms", nullable = false)
    private int processingMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "late_ms")
    private Long lateMs;

    protected MonitoringAiDetection() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getMediaBindingId() {
        return mediaBindingId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public AiProvenance getProvenance() {
        return provenance;
    }

    public AiDetectionType getEventType() {
        return eventType;
    }

    public String getModelId() {
        return modelId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getRuntime() {
        return runtime;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public int getProcessingMs() {
        return processingMs;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Long getLateMs() {
        return lateMs;
    }
}
