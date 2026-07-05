package com.hhtuann.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the V9 {@code grades} table — the formal grading result
 * for an attempt.
 *
 * <p>Created (Day 8) with {@link GradeStatus#AUTO_GRADED}; the release flow
 * transitions to {@link GradeStatus#RELEASED} (which sets {@code released_at}).
 * The DB enforces score caps ({@code auto/final <= max_score}), the
 * RELEASED⇔released_at invariant, and {@code graded_by} RESTRICT; the entity
 * repeats the score guards defensively. This entity contains no grading
 * algorithm — that belongs to Day 8.
 */
@Entity
@Table(name = "grades")
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "automatic_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal automaticScore;

    @Column(name = "final_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal finalScore;

    @Column(name = "max_score", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "percentage", precision = 7, scale = 4)
    private BigDecimal percentage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private GradeStatus status = GradeStatus.AUTO_GRADED;

    @Column(name = "graded_at", nullable = false)
    private Instant gradedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "graded_by")
    private Long gradedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Grade() {
    }

    /**
     * Factory producing an AUTO_GRADED result ({@code released_at} null).
     * {@code gradedBy} is optional (nullable). Scores must satisfy the DB caps.
     */
    public Grade(Long attemptId, BigDecimal automaticScore, BigDecimal finalScore,
                 BigDecimal maxScore, Instant gradedAt, Long gradedBy) {
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.maxScore = requirePositive(maxScore, "maxScore");
        this.automaticScore = requireCapped(automaticScore, "automaticScore", this.maxScore);
        this.finalScore = requireCapped(finalScore, "finalScore", this.maxScore);
        this.gradedAt = Objects.requireNonNull(gradedAt, "gradedAt must not be null");
        this.gradedBy = gradedBy;
    }

    /**
     * Transitions AUTO_GRADED -> RELEASED, atomically setting {@code released_at}.
     * Releasing an already-released grade is rejected.
     */
    public void release(Instant releasedAt) {
        if (this.status != GradeStatus.AUTO_GRADED) {
            throw new IllegalStateException("cannot release grade in status " + status);
        }
        Objects.requireNonNull(releasedAt, "releasedAt must not be null");
        this.status = GradeStatus.RELEASED;
        this.releasedAt = releasedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public BigDecimal getAutomaticScore() {
        return automaticScore;
    }

    public BigDecimal getFinalScore() {
        return finalScore;
    }

    public BigDecimal getMaxScore() {
        return maxScore;
    }

    public BigDecimal getPercentage() {
        return percentage;
    }

    public void setPercentage(BigDecimal percentage) {
        this.percentage = percentage;
    }

    public GradeStatus getStatus() {
        return status;
    }

    public Instant getGradedAt() {
        return gradedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public Long getGradedBy() {
        return gradedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Grade other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Grade.class.hashCode();
    }

    @Override
    public String toString() {
        return "Grade{"
                + "id=" + id
                + ", attemptId=" + attemptId
                + ", finalScore=" + finalScore
                + ", maxScore=" + maxScore
                + ", status=" + status
                + ", gradedAt=" + gradedAt
                + '}';
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static BigDecimal requireCapped(BigDecimal value, String name, BigDecimal max) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be 0.." + name + "max");
        }
        return value;
    }
}
