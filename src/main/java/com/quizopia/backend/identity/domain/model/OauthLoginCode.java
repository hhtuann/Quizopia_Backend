package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping to {@code oauth_login_codes}: a single-use, short-lived,
 * opaque code minted by the OIDC login success handler and exchanged by the
 * SPA at {@code POST /api/auth/oauth/exchange}. Only a SHA-256 hash is stored.
 * The value itself never carries a JWT or refresh token (ADR-002 §4).
 */
@Entity
@Table(name = "oauth_login_codes", indexes = {
        @Index(name = "idx_oauth_login_codes_user", columnList = "user_id")
})
public class OauthLoginCode {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex of the raw 256-bit code. */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OauthLoginCode() {
    }

    public OauthLoginCode(UUID id, User user, String codeHash, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void markConsumed(Instant at) {
        this.consumedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getCodeHash() {
        return codeHash;
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

    @Override
    public String toString() {
        return "OauthLoginCode{id=" + id
                + ", userId=" + (user != null ? user.getId() : null)
                + ", expiresAt=" + expiresAt
                + ", consumedAt=" + consumedAt
                + ", createdAt=" + createdAt + '}';
    }
}
