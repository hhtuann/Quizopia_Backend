package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping {@code community_moderation_actions} — the
 * append-only moderation audit trail (ADR-006 §3 V27 / §5). Rows are
 * INSERT-only: the entity exposes no setters, no state-transition
 * methods and no {@code @Version}; the repository surface is
 * {@code save} + reads only.
 *
 * <p>HIDE/RESTORE carry the publication's {@code from}/{@code to}
 * moderation states (status columns record the unchanged publication
 * status for context); REPORT_DISMISS/REPORT_RESOLVE carry the report
 * id; MODERATOR_GRANTED/MODERATOR_REVOKED are appended by the operator
 * runbook with the dedicated operator platform account as actor.
 */
@Entity
@Table(name = "community_moderation_actions")
public class CommunityModerationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "publication_id", updatable = false)
    private Long publicationId;

    @Column(name = "report_id", updatable = false)
    private Long reportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40, updatable = false)
    private CommunityModerationActionType action;

    @Column(name = "from_status", length = 20, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", length = 20, updatable = false)
    private String toStatus;

    @Column(name = "from_moderation_state", length = 20, updatable = false)
    private String fromModerationState;

    @Column(name = "to_moderation_state", length = 20, updatable = false)
    private String toModerationState;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityModerationAction() {
    }

    public CommunityModerationAction(Long actorUserId, Long publicationId, Long reportId,
            CommunityModerationActionType action, String fromStatus, String toStatus,
            String fromModerationState, String toModerationState, String reason,
            String correlationId) {
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        this.publicationId = publicationId;
        this.reportId = reportId;
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("audit reason must not be blank");
        }
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.fromModerationState = fromModerationState;
        this.toModerationState = toModerationState;
        this.correlationId = correlationId;
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Long getReportId() {
        return reportId;
    }

    public CommunityModerationActionType getAction() {
        return action;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getFromModerationState() {
        return fromModerationState;
    }

    public String getToModerationState() {
        return toModerationState;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
