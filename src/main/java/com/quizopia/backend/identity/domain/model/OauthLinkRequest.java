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
 * JPA entity mapping to {@code oauth_link_requests} (review M1-06): the
 * server-side state binding one Google LINKING authorization request to the
 * already-authenticated Quizopia user who passed re-authentication
 * (password). Only the SHA-256 of the opaque {@code state} sent to Google is
 * stored; {@code code_verifier} and {@code nonce} are needed to complete the
 * PKCE + nonce-protected callback. Single-use, five-minute TTL.
 */
@Entity
@Table(name = "oauth_link_requests", indexes = {
        @Index(name = "idx_oauth_link_requests_user", columnList = "user_id")
})
public class OauthLinkRequest {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex of the raw state parameter sent to Google. */
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "credential_epoch", nullable = false)
    private int credentialEpoch;

    @Column(name = "code_verifier", nullable = false, length = 128)
    private String codeVerifier;

    @Column(name = "nonce", nullable = false, length = 128)
    private String nonce;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OauthLinkRequest() {
    }

    public OauthLinkRequest(UUID id, User user, String stateHash,
            String codeVerifier, String nonce, Instant expiresAt) {
        this.id = id;
        this.user = user;
        this.credentialEpoch = user.getTokenVersion();
        this.stateHash = stateHash;
        this.codeVerifier = codeVerifier;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public void markConsumed(Instant at) {
        Instant effective = consumedAt != null && consumedAt.isAfter(at) ? consumedAt : at;
        this.consumedAt = laterOf(effective, createdAt);
    }

    private Instant laterOf(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public int getCredentialEpoch() {
        return credentialEpoch;
    }

    public String getStateHash() {
        return stateHash;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public String getNonce() {
        return nonce;
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
        return "OauthLinkRequest{id=" + id
                + ", userId=" + (user != null ? user.getId() : null)
                + ", expiresAt=" + expiresAt
                + ", consumedAt=" + consumedAt
                + ", createdAt=" + createdAt + '}';
    }
}
