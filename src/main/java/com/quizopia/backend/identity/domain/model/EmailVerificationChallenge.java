package com.quizopia.backend.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping to {@code email_verification_challenges}. The OTP is
 * stored only as an HMAC-SHA256 hex digest; the plaintext value exists solely
 * inside the outgoing mail and is never logged (ADR-002 §2).
 */
@Entity
@Table(name = "email_verification_challenges", indexes = {
        @Index(name = "idx_email_challenges_user", columnList = "user_id"),
        @Index(name = "idx_email_challenges_created_ip", columnList = "request_ip, created_at")
})
public class EmailVerificationChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Business purpose; guards future reuse of the table. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 50)
    private EmailChallengePurpose purpose;

    /** HMAC-SHA256 hex of the 6-digit OTP. Never the plaintext. */
    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailChallengeStatus status = EmailChallengeStatus.PENDING;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    /** Whether the OTP mail was handed to the transport successfully. */
    @Column(name = "delivered", nullable = false)
    private boolean delivered = false;

    /**
     * Provided by the service from the SAME clock that derives expires_at —
     * a single time source keeps chk_email_challenges_expiry valid even when
     * the injected clock differs from the JVM clock (tests use MutableClock).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmailVerificationChallenge() {
    }

    public EmailVerificationChallenge(User user, EmailChallengePurpose purpose,
            String otpHash, Instant createdAt, Instant expiresAt, String requestIp) {
        this.user = user;
        this.purpose = purpose;
        this.otpHash = otpHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.requestIp = requestIp;
    }

    public void markConsumed(Instant at) {
        this.status = EmailChallengeStatus.CONSUMED;
        this.consumedAt = at;
    }

    public void markSuperseded(Instant at) {
        this.status = EmailChallengeStatus.SUPERSEDED;
        this.supersededAt = at;
    }

    public void markFailed(Instant at) {
        this.status = EmailChallengeStatus.FAILED;
        this.failedAt = at;
    }

    public void incrementFailedAttempts() {
        this.failedAttempts++;
    }

    public void markDelivered() {
        this.delivered = true;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public EmailChallengePurpose getPurpose() {
        return purpose;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public EmailChallengeStatus getStatus() {
        return status;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public String getRequestIp() {
        return requestIp;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        // No OTP hash, no IP: identifiers and lifecycle state only.
        return "EmailVerificationChallenge{id=" + id
                + ", userId=" + (user != null ? user.getId() : null)
                + ", purpose=" + purpose
                + ", status=" + status
                + ", failedAttempts=" + failedAttempts
                + ", expiresAt=" + expiresAt
                + ", delivered=" + delivered
                + ", createdAt=" + createdAt + '}';
    }
}
