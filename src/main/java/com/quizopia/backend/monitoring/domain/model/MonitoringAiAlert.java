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
import java.util.Set;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * JPA entity mapping the V38 {@code monitoring_ai_alerts} table — one
 * temporal aggregation window per (attempt, alertType) incident (P4-C,
 * issue #90, CONTRACT §5).
 *
 * <p><b>Window semantics:</b> a detection within the cooldown of
 * {@code lastDetectedAt} EXTENDS the OPEN window (count+1, max confidence,
 * latest payload); a gap beyond the cooldown CLOSES it (status CLOSED) and
 * the next detection opens a NEW window — every incident keeps its own
 * first/last/count evidence. The partial unique index
 * {@code uk_ai_alerts_open} enforces exactly one OPEN window per
 * (attempt_id, alert_type).
 *
 * <p><b>Observations, never verdicts (CONTRACT §8):</b> alerts drive the
 * teacher feed only — no auto cheating conclusion, no score impact, no FK
 * or write path into {@code grades}/{@code grade_items}.
 */
@Entity
@Table(name = "monitoring_ai_alerts")
public class MonitoringAiAlert {

    /** Live window state (V38 CHECK admits OPEN and CLOSED). */
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    /** Current human-review states (V42 CHECK) — never a misconduct verdict. */
    public static final String REVIEW_UNREVIEWED = "UNREVIEWED";
    public static final Set<String> REVIEW_STATES = Set.of(REVIEW_UNREVIEWED,
            "VIEWED", "FALSE_POSITIVE", "NEEDS_FOLLOW_UP", "NOTED",
            "REVIEWED_NO_CONCLUSION");

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

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 40)
    private AiAlertType alertType;

    @Column(name = "status", nullable = false, length = 12)
    private String status = STATUS_OPEN;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount = 1;

    @Column(name = "max_confidence")
    private Double maxConfidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "latest_payload", columnDefinition = "jsonb")
    private JsonNode latestPayload;

    @Column(name = "model_id", nullable = false, length = 40)
    private String modelId;

    @Column(name = "model_version", nullable = false, length = 40)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 20)
    private AiProvenance provenance;

    // P4-E denormalized current review state (V42). Detector fields above
    // are NEVER rewritten by a review — only these four move, in the same
    // transaction as the ledger append.
    @Column(name = "review_status", nullable = false, length = 24)
    private String reviewStatus = REVIEW_UNREVIEWED;

    @Column(name = "review_version", nullable = false)
    private int reviewVersion = 0;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoringAiAlert() {
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

    public AiAlertType getAlertType() {
        return alertType;
    }

    public String getStatus() {
        return status;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public Instant getLastDetectedAt() {
        return lastDetectedAt;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public Double getMaxConfidence() {
        return maxConfidence;
    }

    public JsonNode getLatestPayload() {
        return latestPayload;
    }

    public String getModelId() {
        return modelId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public AiProvenance getProvenance() {
        return provenance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public int getReviewVersion() {
        return reviewVersion;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    /**
     * Applies the denormalized current-review state after the ledger row is
     * appended in the SAME transaction (AlertReviewService). Caller has
     * already verified optimistic concurrency on {@code reviewVersion}.
     */
    public void applyReview(int newVersion, String action, Long reviewerUserId,
            Instant reviewedAt) {
        this.reviewVersion = newVersion;
        this.reviewStatus = action;
        this.reviewedBy = reviewerUserId;
        this.reviewedAt = reviewedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
