package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the V37 {@code monitoring_media_bindings} table — the
 * media-identity ↔ attempt binding with epochs (P4-B2/B4, issues #85/#87).
 *
 * <p><b>Identity semantics (ADR-08):</b> {@code mediaIdentity} is a fresh
 * opaque UUID created with its preflight BEFORE any attempt exists; it is
 * bound to the exact attempt atomically at start ({@link #markBound}) inside
 * the start transaction. A late webhook therefore resolves by identity+epoch
 * and can never be reattributed to another attempt of the same user.
 *
 * <p><b>Epoch:</b> monotonically increasing per (session, user) — a revoked
 * or superseded binding yields its identity forever and the next preflight
 * mints a new identity at the next epoch.
 *
 * <p><b>Validity:</b> DB CHECK {@code chk_bindings_unbound_still_active}
 * enforces "unbound bindings stay active": {@code attemptId} is null only
 * while {@code activeTo} is null (revocation/close always sets
 * {@code activeTo}).
 */
@Entity
@Table(name = "monitoring_media_bindings")
public class MonitoringMediaBinding {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "preflight_id", nullable = false)
    private UUID preflightId;

    /** Null until the monitored start consumes this binding's preflight lease. */
    @Column(name = "attempt_id")
    private Long attemptId;

    @Column(name = "media_identity", nullable = false)
    private String mediaIdentity;

    @Column(name = "epoch", nullable = false)
    private int epoch;

    @Column(name = "active_from", nullable = false, updatable = false)
    private Instant activeFrom;

    /** Null while active; set on revoke (teacher) or close (housekeeping). */
    @Column(name = "active_to")
    private Instant activeTo;

    /** Non-null only for teacher revocations (never for housekeeping closes). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected MonitoringMediaBinding() {
        // JPA
    }

    /**
     * Factory for the binding created together with its preflight: unbound
     * ({@code attemptId} null) and active ({@code activeTo} null).
     */
    public static MonitoringMediaBinding create(UUID id, Long examSessionId, Long userId,
            UUID preflightId, String mediaIdentity, int epoch, Instant now) {
        MonitoringMediaBinding b = new MonitoringMediaBinding();
        b.id = id;
        b.examSessionId = examSessionId;
        b.userId = userId;
        b.preflightId = preflightId;
        b.mediaIdentity = mediaIdentity;
        b.epoch = epoch;
        b.activeFrom = now;
        return b;
    }

    /**
     * Binds the binding to the exact attempt created by the lease-consuming
     * start (same transaction as the lease spend; the one-time lease and the
     * binding commit or roll back atomically).
     */
    public void markBound(Long attemptId) {
        this.attemptId = attemptId;
    }

    /**
     * Teacher revocation: closes the binding AND records the revocation
     * instant (distinguishing it from housekeeping closes).
     */
    public void markRevoked(Instant now) {
        this.activeTo = now;
        this.revokedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getPreflightId() {
        return preflightId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public String getMediaIdentity() {
        return mediaIdentity;
    }

    public int getEpoch() {
        return epoch;
    }

    public Instant getActiveFrom() {
        return activeFrom;
    }

    public Instant getActiveTo() {
        return activeTo;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
