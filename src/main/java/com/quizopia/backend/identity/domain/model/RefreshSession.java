package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA Entity mapping to the {@code refresh_sessions} table.
 * Stores hashed opaque refresh tokens and rotation history.
 * <p>
 * <strong>Security:</strong> Never log or expose tokenHash in API responses.
 * The original refresh token must never be stored.
 */
@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID familyId;

    /**
     * Hash of the opaque refresh token.
     * <p>
     * <strong>Security:</strong> Never log or expose this value.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * The parent session that was replaced by this one (token rotation).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_session_id", unique = true)
    private RefreshSession parentSession;

    /**
     * The session that replaced this one (token rotation).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_session_id", unique = true)
    private RefreshSession replacedBySession;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_ip")
    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress createdIp;

    @Column(name = "last_used_ip")
    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress lastUsedIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 100)
    private String revokeReason;

    /**
     * Default constructor required by JPA.
     */
    protected RefreshSession() {
    }

    /**
     * Constructor for creating a new refresh session.
     *
     * @param id        the unique UUID for this session
     * @param user      the user who owns this session
     * @param familyId  the family ID for token rotation tracking
     * @param tokenHash the hash of the opaque refresh token
     * @param userAgent optional user agent string
     * @param createdIp optional IP address of the client that created this session
     * @param expiresAt when this session expires
     */
    public RefreshSession(UUID id, User user, UUID familyId, String tokenHash,
                         String userAgent, InetAddress createdIp, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.userAgent = userAgent;
        this.createdIp = createdIp;
        this.expiresAt = expiresAt;
    }

    // ============================================================
    // Getters and Setters
    // ============================================================

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void setFamilyId(UUID familyId) {
        this.familyId = familyId;
    }

    /**
     * Returns the token hash.
     * <p>
     * <strong>Security:</strong> Never log or expose this value in API responses.
     *
     * @return the hash of the opaque refresh token
     */
    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public RefreshSession getParentSession() {
        return parentSession;
    }

    public void setParentSession(RefreshSession parentSession) {
        this.parentSession = parentSession;
    }

    public RefreshSession getReplacedBySession() {
        return replacedBySession;
    }

    public void setReplacedBySession(RefreshSession replacedBySession) {
        this.replacedBySession = replacedBySession;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public InetAddress getCreatedIp() {
        return createdIp;
    }

    public void setCreatedIp(InetAddress createdIp) {
        this.createdIp = createdIp;
    }

    public InetAddress getLastUsedIp() {
        return lastUsedIp;
    }

    public void setLastUsedIp(InetAddress lastUsedIp) {
        this.lastUsedIp = lastUsedIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
    }

    // ============================================================
    // equals and hashCode (based on ID only)
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RefreshSession other)) {
            return false;
        }

        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return RefreshSession.class.hashCode();
    }

    // ============================================================
    // toString (excludes sensitive and association fields)
    // ============================================================

    @Override
    public String toString() {
        return "RefreshSession{" +
                "id=" + id +
                ", familyId=" + familyId +
                ", userAgent='" + userAgent + '\'' +
                ", createdIp=" + createdIp +
                ", lastUsedIp=" + lastUsedIp +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                ", lastUsedAt=" + lastUsedAt +
                ", revokedAt=" + revokedAt +
                ", revokeReason='" + revokeReason + '\'' +
                '}';
    }
}
