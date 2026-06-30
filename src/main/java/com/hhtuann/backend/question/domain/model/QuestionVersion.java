package com.hhtuann.backend.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code question_versions} table. A version holds the
 * immutable content of a question at a given {@code version_number}.
 *
 * <p>Application-level immutability: once persisted, a version is never updated;
 * editing a question creates a new version. This entity therefore exposes no
 * mutating setters for its content fields.
 *
 * <p>{@code answer_key} and {@code metadata} are mapped as native JSONB
 * ({@link SqlTypes#JSON} with {@link JsonNode}) so the JSON value types are
 * preserved (e.g. a numeric {@code requiredInputLength} stays a JSON number,
 * not a string). {@code answer_key} is non-null only for {@link QuestionType#NUMERIC_FILL}.
 */
@Entity
@Table(name = "question_versions")
public class QuestionVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 40)
    private QuestionType questionType;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "explanation")
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private QuestionDifficulty difficulty = QuestionDifficulty.MEDIUM;

    @Column(name = "default_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultPoints = BigDecimal.ONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_key", columnDefinition = "jsonb")
    private JsonNode answerKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private JsonNode metadata = JsonNodeFactory.instance.objectNode();

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuestionVersion() {
    }

    /**
     * Creates a question version with default {@code difficulty=MEDIUM},
     * {@code explanation=null}, {@code answerKey=null} and an empty
     * {@code metadata} object. Use the full constructor for NUMERIC_FILL
     * versions that need an {@code answer_key}.
     *
     * @param questionId    the parent question id (not null)
     * @param versionNumber the version number (>= 1)
     * @param questionType  the question type (not null)
     * @param content       the question content (not blank)
     * @param createdBy     the creating user id (not null)
     * @param defaultPoints the default points (> 0)
     */
    public QuestionVersion(Long questionId, Integer versionNumber, QuestionType questionType,
                           String content, Long createdBy, BigDecimal defaultPoints) {
        this(questionId, versionNumber, questionType, content, null,
                QuestionDifficulty.MEDIUM, defaultPoints, null, null, createdBy);
    }

    /**
     * Full constructor used to create an immutable version, including the
     * NUMERIC_FILL {@code answer_key} and {@code metadata}.
     *
     * @param metadata if null, an empty JSON object is used
     */
    public QuestionVersion(Long questionId, Integer versionNumber, QuestionType questionType,
                           String content, String explanation, QuestionDifficulty difficulty,
                           BigDecimal defaultPoints, JsonNode answerKey, JsonNode metadata,
                           Long createdBy) {
        this.questionId = QuestionBank.requireNonNullId(questionId, "questionId");
        this.versionNumber = requirePositiveVersion(versionNumber);
        this.questionType = Objects.requireNonNull(questionType, "questionType must not be null");
        this.content = QuestionBank.requireNonBlank("content", content);
        this.explanation = explanation;
        this.difficulty = difficulty != null ? difficulty : QuestionDifficulty.MEDIUM;
        this.defaultPoints = requirePositivePoints(defaultPoints);
        this.answerKey = answerKey;
        this.metadata = metadata != null ? metadata : JsonNodeFactory.instance.objectNode();
        this.createdBy = QuestionBank.requireNonNullId(createdBy, "createdBy");
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public QuestionType getQuestionType() {
        return questionType;
    }

    public String getContent() {
        return content;
    }

    public String getExplanation() {
        return explanation;
    }

    public QuestionDifficulty getDifficulty() {
        return difficulty;
    }

    public BigDecimal getDefaultPoints() {
        return defaultPoints;
    }

    public JsonNode getAnswerKey() {
        return answerKey;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public Long getCreatedBy() {
        return createdBy;
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
        if (!(o instanceof QuestionVersion other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return QuestionVersion.class.hashCode();
    }

    @Override
    public String toString() {
        return "QuestionVersion{" +
                "id=" + id +
                ", questionId=" + questionId +
                ", versionNumber=" + versionNumber +
                ", questionType=" + questionType +
                ", difficulty=" + difficulty +
                ", defaultPoints=" + defaultPoints +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    private static Integer requirePositiveVersion(Integer versionNumber) {
        Objects.requireNonNull(versionNumber, "versionNumber must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be >= 1");
        }
        return versionNumber;
    }

    private static BigDecimal requirePositivePoints(BigDecimal defaultPoints) {
        Objects.requireNonNull(defaultPoints, "defaultPoints must not be null");
        if (defaultPoints.signum() <= 0) {
            throw new IllegalArgumentException("defaultPoints must be > 0");
        }
        return defaultPoints;
    }
}
