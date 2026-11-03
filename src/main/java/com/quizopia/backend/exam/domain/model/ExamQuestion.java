package com.quizopia.backend.exam.domain.model;

import com.quizopia.backend.question.domain.model.QuestionDifficulty;
import com.quizopia.backend.question.domain.model.QuestionType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;

/**
 * JPA entity mapping the {@code exam_questions} table — an immutable snapshot
 * of a Question Bank question version, used for attempt/grading. Belongs
 * directly to an Exam (one-exam-one-content, issue #2).
 *
 * <p><b>Provenance:</b> two writable scalar columns
 * ({@code source_question_id}, {@code source_question_version_id}) point at the
 * pinned immutable Question Bank version the snapshot was taken from. The
 * composite DB FK targets
 * {@code uk_question_versions_question_id UNIQUE(question_id, id)}.
 *
 * <p>{@code answerKey} and {@code metadata} are mapped as native JSONB via
 * {@link SqlTypes#JSON} with {@link JsonNode}; {@code metadata} is initialised
 * per-instance to an empty object (no shared mutable state). The
 * {@code toString()} excludes {@code answerKey} to avoid leaking answers.
 */
@Entity
@Table(name = "exam_questions")
public class ExamQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "exam_section_id", nullable = false)
    private Long examSectionId;

    @Column(name = "source_question_id", nullable = false)
    private Long sourceQuestionId;

    @Column(name = "source_question_version_id", nullable = false)
    private Long sourceQuestionVersionId;

    @Column(name = "question_code", nullable = false, length = 80)
    private String questionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 40)
    private QuestionType questionType;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "default_points", nullable = false, precision = 10, scale = 2)
    private BigDecimal defaultPoints;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", length = 20)
    private QuestionDifficulty difficulty;

    @Column(name = "explanation")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_key", columnDefinition = "jsonb")
    private JsonNode answerKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private JsonNode metadata = JsonNodeFactory.instance.objectNode();

    @Column(name = "position", nullable = false)
    private Integer position;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    protected ExamQuestion() {
    }

    public ExamQuestion(Long examId, Long examSectionId,
                        Long sourceQuestionId, Long sourceQuestionVersionId,
                        String questionCode, QuestionType questionType,
                        String content, BigDecimal defaultPoints, Integer position) {
        this.examId = ExamPurpose.requireNonNullId(examId, "examId");
        this.examSectionId = ExamPurpose.requireNonNullId(examSectionId, "examSectionId");
        this.sourceQuestionId = ExamPurpose.requireNonNullId(sourceQuestionId, "sourceQuestionId");
        this.sourceQuestionVersionId = ExamPurpose.requireNonNullId(sourceQuestionVersionId, "sourceQuestionVersionId");
        this.questionCode = ExamPurpose.requireNonBlank("questionCode", questionCode);
        this.questionType = java.util.Objects.requireNonNull(questionType, "questionType must not be null");
        this.content = ExamPurpose.requireNonBlank("content", content);
        this.defaultPoints = requirePositivePoints(defaultPoints);
        this.position = requireNonNegative(position, "position");
    }

    public Long getId() {
        return id;
    }

    public Long getExamId() {
        return examId;
    }

    public Long getExamSectionId() {
        return examSectionId;
    }

    public Long getSourceQuestionId() {
        return sourceQuestionId;
    }

    /**
     * The pinned immutable source Question Bank version id (provenance;
     * never mutated after publish).
     */
    public Long getSourceQuestionVersionId() {
        return sourceQuestionVersionId;
    }

    public String getQuestionCode() {
        return questionCode;
    }

    public QuestionType getQuestionType() {
        return questionType;
    }

    public String getContent() {
        return content;
    }

    public BigDecimal getDefaultPoints() {
        return defaultPoints;
    }

    public QuestionDifficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(QuestionDifficulty difficulty) {
        this.difficulty = difficulty;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public JsonNode getAnswerKey() {
        return answerKey;
    }

    /**
     * Sets the answer key for this snapshot. Used by the service during PUT
     * composition to store the NUMERIC_FILL answer key (null for choice/TF).
     */
    public void setAnswerKey(JsonNode answerKey) {
        this.answerKey = answerKey;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }

    public Integer getPosition() {
        return position;
    }

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamQuestion other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamQuestion.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamQuestion{" +
                "id=" + id +
                ", examId=" + examId +
                ", examSectionId=" + examSectionId +
                ", sourceQuestionId=" + sourceQuestionId +
                ", sourceQuestionVersionId=" + sourceQuestionVersionId +
                ", questionCode='" + questionCode + '\'' +
                ", questionType=" + questionType +
                ", difficulty=" + difficulty +
                ", position=" + position +
                ", createdAt=" + createdAt +
                '}';
    }

    private static BigDecimal requirePositivePoints(BigDecimal points) {
        java.util.Objects.requireNonNull(points, "defaultPoints must not be null");
        if (points.signum() <= 0) {
            throw new IllegalArgumentException("defaultPoints must be > 0");
        }
        return points;
    }

    private static Integer requireNonNegative(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
        return value;
    }
}
