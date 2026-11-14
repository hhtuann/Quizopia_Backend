package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the V37 {@code monitoring_preflights} table — the camera
 * preflight per (session, user) with a one-time hashed readiness lease
 * (P4-B2, issue #85).
 *
 * <p><b>Raw lease is NEVER stored</b> (ADR-008 invariant): the client-held
 * {@code readinessLease} string exists only in the verify response and the
 * start request; the row keeps {@code leaseHash} = sha256 hex of the raw
 * value. The DB CHECK {@code chk_preflights_lease_pair} keeps hash and
 * expiry in lockstep (both null until verified).
 *
 * <p><b>Lease rotation:</b> a repeat verify of a still-valid preflight mints
 * a NEW lease and overwrites hash+expiry (the raw lease of the previous grant
 * cannot be recovered from the hash, so re-issuing the same value is
 * impossible by construction). Status stays {@code VERIFIED}.
 *
 * <p><b>One-time spend:</b> {@code CONSUMED} is reached only through the
 * guarded bulk update in {@code MonitoringPreflightRepository.consumeVerified}
 * ({@code WHERE status = 'VERIFIED'}, exactly one row) under the preflight row
 * lock — a concurrent double start spends the lease exactly once.
 */
@Entity
@Table(name = "monitoring_preflights")
public class MonitoringPreflight {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Opaque per-preflight LiveKit identity (fresh UUID; never reused). */
    @Column(name = "media_identity", nullable = false, unique = true)
    private String mediaIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PreflightStatus status;

    /** sha256 hex (64 chars) of the raw lease; null until verified. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "lease_hash", unique = true)
    private String leaseHash;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "verified_track_sid")
    private String verifiedTrackSid;

    /** Preflight lifetime (created + preflightTtl, default 5 minutes). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MonitoringPreflight() {
        // JPA
    }

    /**
     * Factory for a freshly created preflight: status ACTIVE, no lease yet
     * (the lease appears only at verification).
     */
    public static MonitoringPreflight create(UUID id, Long examSessionId, Long userId,
            String mediaIdentity, Instant expiresAt, Instant now) {
        MonitoringPreflight p = new MonitoringPreflight();
        p.id = id;
        p.examSessionId = examSessionId;
        p.userId = userId;
        p.mediaIdentity = mediaIdentity;
        p.status = PreflightStatus.ACTIVE;
        p.expiresAt = expiresAt;
        p.createdAt = now;
        return p;
    }

    /**
     * Marks the preflight VERIFIED with a (possibly rotated) lease grant.
     * Callers pass {@code leaseHash} = sha256 hex of a freshly minted raw
     * lease — never the raw lease itself.
     */
    public void markVerified(String leaseHash, Instant leaseExpiresAt, String trackSid) {
        this.status = PreflightStatus.VERIFIED;
        this.leaseHash = leaseHash;
        this.leaseExpiresAt = leaseExpiresAt;
        this.verifiedTrackSid = trackSid;
    }

    /** Terminal teacher-revocation state (binding revoke path). */
    public void markRevoked() {
        this.status = PreflightStatus.REVOKED;
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

    public String getMediaIdentity() {
        return mediaIdentity;
    }

    public PreflightStatus getStatus() {
        return status;
    }

    public String getLeaseHash() {
        return leaseHash;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getVerifiedTrackSid() {
        return verifiedTrackSid;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
