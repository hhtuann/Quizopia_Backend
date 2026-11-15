package com.quizopia.backend.evidence.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Audit row for every evidence URL mint (and playback authorization) —
 * V40 {@code monitoring_evidence_access} (P4-D #94).
 */
@Entity
@Table(name = "monitoring_evidence_access")
public class MonitoringEvidenceAccess {

    public static final String ACTION_URL_MINTED = "URL_MINTED";
    public static final String ACTION_PLAYBACK = "PLAYBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "evidence_id", nullable = false)
    private java.util.UUID evidenceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    protected MonitoringEvidenceAccess() {
        // JPA
    }

    public static MonitoringEvidenceAccess of(java.util.UUID evidenceId, Long userId,
            String action, Instant accessedAt) {
        MonitoringEvidenceAccess row = new MonitoringEvidenceAccess();
        row.evidenceId = evidenceId;
        row.userId = userId;
        row.action = action;
        row.accessedAt = accessedAt;
        return row;
    }

    public Long getId() {
        return id;
    }

    public java.util.UUID getEvidenceId() {
        return evidenceId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }
}
