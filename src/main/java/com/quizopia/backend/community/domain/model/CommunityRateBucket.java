package com.quizopia.backend.community.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping {@code community_rate_buckets} (ADR-006 §3/§9): the
 * generalized V23 abuse-bucket shape backing {@code CommunityRateLimiter}.
 * Rows are manipulated only through the native upsert/lock/update queries
 * on {@code CommunityRateBucketRepository} ({@code ON CONFLICT DO
 * NOTHING} → {@code SELECT … FOR UPDATE} → {@code UPDATE}).
 */
@Entity
@Table(name = "community_rate_buckets")
public class CommunityRateBucket {

    /** Domain-separated HMAC-SHA-256 of the keyed identity (64 hex). */
    @Id
    @Column(name = "bucket_key", nullable = false, length = 64)
    private String bucketKey;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_admitted_at")
    private Instant lastAdmittedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected CommunityRateBucket() {
    }

    public String getBucketKey() {
        return bucketKey;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getLastAdmittedAt() {
        return lastAdmittedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
