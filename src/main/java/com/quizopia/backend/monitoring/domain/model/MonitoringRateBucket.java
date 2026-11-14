package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * JPA entity mapping the V34 {@code monitoring_rate_buckets} table —
 * fixed-window admission for activity-event ingestion (P4-A2, issue #81).
 * Mechanics mirror {@code CommunityRateBucket} (ADR-006 §9 pattern):
 * HMAC-hashed bucket keys (no plaintext user ids), upsert → FOR UPDATE →
 * update, fail-closed.
 */
@Entity
@Table(name = "monitoring_rate_buckets")
public class MonitoringRateBucket {

    @Id
    @Column(name = "bucket_key", length = 64)
    private String bucketKey;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_admitted_at")
    private Instant lastAdmittedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected MonitoringRateBucket() {
        // JPA
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
