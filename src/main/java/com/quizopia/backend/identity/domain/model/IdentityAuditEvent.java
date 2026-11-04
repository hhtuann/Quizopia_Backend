package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapping to {@code identity_audit_events}: an append-only record
 * of identity lifecycle decisions (Identity-M2). No secrets, no OTPs, no
 * tokens — only who did what, when, from which client (ADR-002 §5).
 */
@Entity
@Table(name = "identity_audit_events", indexes = {
        @Index(name = "idx_identity_audit_events_user", columnList = "user_id, created_at")
})
public class IdentityAuditEvent {

    public static final String TEACHER_SELF_UPGRADE = "TEACHER_SELF_UPGRADE";
    public static final String TEACHER_SELF_UPGRADE_IDEMPOTENT = "TEACHER_SELF_UPGRADE_IDEMPOTENT";
    public static final String TEACHER_SELF_UPGRADE_REACTIVATED = "TEACHER_SELF_UPGRADE_REACTIVATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** Minimal, non-sensitive context (e.g. prior role codes). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private java.util.Map<String, Object> details = new java.util.HashMap<>();

    protected IdentityAuditEvent() {
    }

    public IdentityAuditEvent(User user, String eventType,
            String ip, String userAgent, java.util.Map<String, Object> details) {
        this.user = user;
        this.eventType = eventType;
        this.ip = ip;
        this.userAgent = userAgent == null || userAgent.length() <= 500 ? userAgent
                : userAgent.substring(0, 500);
        if (details != null) {
            this.details.putAll(details);
        }
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public java.util.Map<String, Object> getDetails() {
        return details;
    }

    @Override
    public String toString() {
        return "IdentityAuditEvent{id=" + id
                + ", userId=" + (user != null ? user.getId() : null)
                + ", eventType='" + eventType + '\''
                + ", createdAt=" + createdAt + '}';
    }
}
