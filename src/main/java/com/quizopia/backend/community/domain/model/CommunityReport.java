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
 * JPA entity mapping {@code community_reports} (ADR-006 §3 V27).
 *
 * <p>At most one OPEN row per (reporter, publication) — enforced by the
 * partial unique index {@code uk_community_reports_open_per_reporter};
 * submissions collapse through {@code INSERT … ON CONFLICT … DO NOTHING}
 * + re-read (§7 fixed order), never a caught constraint violation. A
 * handled (DISMISSED/RESOLVED) report never blocks a new OPEN one.
 *
 * <p>{@code handled_by}/{@code handled_at}/{@code resolution_note} are
 * set exactly once, by the moderator dismiss/resolve transition under
 * the report row lock; there is no un-handle path.
 */
@Entity
@Table(name = "community_reports")
public class CommunityReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publication_id", nullable = false, updatable = false)
    private Long publicationId;

    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private CommunityReportReason reasonCode;

    @Column(name = "details", length = 500, updatable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CommunityReportStatus status = CommunityReportStatus.OPEN;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityReport() {
    }

    public CommunityReport(Long publicationId, Long reporterUserId,
            CommunityReportReason reasonCode, String details) {
        this.publicationId = Objects.requireNonNull(publicationId, "publicationId must not be null");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "reporterUserId must not be null");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        this.details = details;
    }

    /** OPEN → DISMISSED under the report row lock; exactly-once. */
    public void dismiss(Long moderatorUserId, Instant handledAt, String note) {
        requireOpen("dismiss");
        this.status = CommunityReportStatus.DISMISSED;
        this.handledBy = moderatorUserId;
        this.handledAt = handledAt;
        this.resolutionNote = note;
    }

    /** OPEN → RESOLVED under the report row lock; exactly-once. */
    public void resolve(Long moderatorUserId, Instant handledAt, String note) {
        requireOpen("resolve");
        this.status = CommunityReportStatus.RESOLVED;
        this.handledBy = moderatorUserId;
        this.handledAt = handledAt;
        this.resolutionNote = note;
    }

    private void requireOpen(String transition) {
        if (status != CommunityReportStatus.OPEN) {
            throw new IllegalStateException(
                    transition + " requires an OPEN report, was " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPublicationId() {
        return publicationId;
    }

    public Long getReporterUserId() {
        return reporterUserId;
    }

    public CommunityReportReason getReasonCode() {
        return reasonCode;
    }

    public String getDetails() {
        return details;
    }

    public CommunityReportStatus getStatus() {
        return status;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
