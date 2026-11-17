package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * JPA entity mapping the V42 {@code monitoring_alert_reviews} table — the
 * append-only teacher review ledger (P4-E, issue #96, ADR-010 §2.2).
 *
 * <p>One row per review action. {@code reviewVersion} is 1-based per alert
 * (unique {@code (alertId, reviewVersion)}); {@code previousAction}/
 * {@code previousReviewVersion} carry the prior state for audit without
 * mutating history. {@code idempotencyKey} is unique — a replayed key returns
 * the stored outcome instead of appending again.
 *
 * <p><b>Observations, never verdicts (CONTRACT §8 / issue #96):</b> reviews
 * annotate AI alerts with human judgement; they never mutate the detector's
 * own fields, never touch {@code grades}/{@code grade_items}, never change
 * attempt or session status.
 */
@Entity
@Table(name = "monitoring_alert_reviews")
public class MonitoringAlertReview {

    /** Allowed review actions (V42 CHECK) — deliberately no CHEATED. */
    public static final String ACTION_MARK_VIEWED = "MARK_VIEWED";
    public static final String ACTION_FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String ACTION_NEEDS_FOLLOW_UP = "NEEDS_FOLLOW_UP";
    public static final String ACTION_NOTED = "NOTED";
    public static final String ACTION_REVIEWED_NO_CONCLUSION = "REVIEWED_NO_CONCLUSION";

    public static final java.util.Set<String> ACTIONS = java.util.Set.of(
            ACTION_MARK_VIEWED, ACTION_FALSE_POSITIVE, ACTION_NEEDS_FOLLOW_UP,
            ACTION_NOTED, ACTION_REVIEWED_NO_CONCLUSION);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "reviewer_user_id", nullable = false)
    private Long reviewerUserId;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "review_version", nullable = false)
    private int reviewVersion;

    @Column(name = "previous_action", length = 24)
    private String previousAction;

    @Column(name = "previous_review_version")
    private Integer previousReviewVersion;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MonitoringAlertReview() {
        // JPA
    }

    private MonitoringAlertReview(Long alertId, Long reviewerUserId, String action,
            String note, int reviewVersion, String previousAction,
            Integer previousReviewVersion, UUID idempotencyKey) {
        this.alertId = alertId;
        this.reviewerUserId = reviewerUserId;
        this.action = action;
        this.note = note;
        this.reviewVersion = reviewVersion;
        this.previousAction = previousAction;
        this.previousReviewVersion = previousReviewVersion;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Appends a new ledger row. The caller owns optimistic-concurrency
     * verification ({@code alert.getReviewVersion() == expectedVersion}) and
     * updates the denormalized alert state in the same transaction.
     */
    public static MonitoringAlertReview append(MonitoringAiAlert alert,
            Long reviewerUserId, String action, String note, UUID idempotencyKey) {
        int nextVersion = alert.getReviewVersion() + 1;
        String previousAction = alert.getReviewVersion() == 0
                ? null
                : alert.getReviewStatus();
        Integer previousVersion = alert.getReviewVersion() == 0
                ? null
                : alert.getReviewVersion();
        return new MonitoringAlertReview(alert.getId(), reviewerUserId, action, note,
                nextVersion, previousAction, previousVersion, idempotencyKey);
    }

    public Long getId() {
        return id;
    }

    public Long getAlertId() {
        return alertId;
    }

    public Long getReviewerUserId() {
        return reviewerUserId;
    }

    public String getAction() {
        return action;
    }

    public String getNote() {
        return note;
    }

    public int getReviewVersion() {
        return reviewVersion;
    }

    public String getPreviousAction() {
        return previousAction;
    }

    public Integer getPreviousReviewVersion() {
        return previousReviewVersion;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
