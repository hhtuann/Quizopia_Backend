package com.quizopia.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the V9 {@code grade_items} table — the per-attempt-question
 * grading verdict (attempt-scoped via {@code attempt_question_id}, NOT
 * {@code exam_question_id}).
 *
 * <p>Created by Day 8 grading. The DB enforces {@code awarded_points <= max_points},
 * {@code is_correct NOT NULL}, and the composite ownership FKs; the entity
 * repeats the awarded-cap guard defensively. No grading algorithm lives here.
 */
@Entity
@Table(name = "grade_items")
public class GradeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "attempt_question_id", nullable = false)
    private Long attemptQuestionId;

    @Column(name = "awarded_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal awardedPoints;

    @Column(name = "max_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal maxPoints;

    @Column(name = "is_correct", nullable = false)
    private boolean isCorrect;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grading_details", nullable = false, columnDefinition = "jsonb")
    private JsonNode gradingDetails;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GradeItem() {
    }

    public GradeItem(Long gradeId, Long attemptId, Long attemptQuestionId,
                     BigDecimal awardedPoints, BigDecimal maxPoints, boolean isCorrect, JsonNode gradingDetails) {
        this.gradeId = Objects.requireNonNull(gradeId, "gradeId must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.attemptQuestionId = Objects.requireNonNull(attemptQuestionId, "attemptQuestionId must not be null");
        this.maxPoints = requirePositive(maxPoints, "maxPoints");
        this.awardedPoints = requireCapped(awardedPoints, "awardedPoints", this.maxPoints);
        this.isCorrect = isCorrect;
        this.gradingDetails = Objects.requireNonNull(gradingDetails, "gradingDetails must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getGradeId() {
        return gradeId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public Long getAttemptQuestionId() {
        return attemptQuestionId;
    }

    public BigDecimal getAwardedPoints() {
        return awardedPoints;
    }

    public BigDecimal getMaxPoints() {
        return maxPoints;
    }

    public boolean isCorrect() {
        return isCorrect;
    }

    public JsonNode getGradingDetails() {
        return gradingDetails;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GradeItem other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return GradeItem.class.hashCode();
    }

    @Override
    public String toString() {
        return "GradeItem{"
                + "id=" + id
                + ", gradeId=" + gradeId
                + ", attemptId=" + attemptId
                + ", attemptQuestionId=" + attemptQuestionId
                + ", awardedPoints=" + awardedPoints
                + ", isCorrect=" + isCorrect
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
            throw new IllegalArgumentException(name + " must be 0..max");
        }
        return value;
    }
}
