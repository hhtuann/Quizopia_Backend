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
 * JPA entity mapping the V9 {@code attempt_questions} table — the stable
 * question/order snapshot frozen at attempt start.
 *
 * <p>{@code question_type} and {@code default_points} are denormalized from the
 * immutable exam-question snapshot so autosave validation and Day 8 grading
 * (max points) are self-contained. {@code option_order} is a nullable JSONB
 * array of option keys (NULL for NUMERIC_FILL). All foreign keys are scalar IDs.
 */
@Entity
@Table(name = "attempt_questions")
public class AttemptQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "exam_question_id", nullable = false)
    private Long examQuestionId;

    @Column(name = "question_type", nullable = false, length = 40)
    private String questionType;

    @Column(name = "default_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultPoints;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "option_order", columnDefinition = "jsonb")
    private JsonNode optionOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AttemptQuestion() {
    }

    public AttemptQuestion(Long attemptId, Long examQuestionId, String questionType,
                           BigDecimal defaultPoints, Integer displayOrder, JsonNode optionOrder) {
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.examQuestionId = Objects.requireNonNull(examQuestionId, "examQuestionId must not be null");
        if (questionType == null || questionType.isBlank()) {
            throw new IllegalArgumentException("questionType must not be blank");
        }
        this.questionType = questionType;
        this.defaultPoints = requirePositivePoints(defaultPoints);
        this.displayOrder = requireNonNegative(displayOrder, "displayOrder");
        this.optionOrder = optionOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public Long getExamQuestionId() {
        return examQuestionId;
    }

    public String getQuestionType() {
        return questionType;
    }

    public BigDecimal getDefaultPoints() {
        return defaultPoints;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public JsonNode getOptionOrder() {
        return optionOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AttemptQuestion other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return AttemptQuestion.class.hashCode();
    }

    @Override
    public String toString() {
        return "AttemptQuestion{"
                + "id=" + id
                + ", attemptId=" + attemptId
                + ", examQuestionId=" + examQuestionId
                + ", questionType='" + questionType + '\''
                + ", defaultPoints=" + defaultPoints
                + ", displayOrder=" + displayOrder
                + '}';
    }

    private static BigDecimal requirePositivePoints(BigDecimal points) {
        Objects.requireNonNull(points, "defaultPoints must not be null");
        if (points.signum() <= 0) {
            throw new IllegalArgumentException("defaultPoints must be > 0");
        }
        return points;
    }

    private static int requireNonNegative(Integer value, String name) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }
}
