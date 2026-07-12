package com.quizopia.backend.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping the {@code question_options} table. An option belongs to a
 * {@link QuestionVersion}. {@code option_key} is constrained to A–F at the
 * database level; for {@link QuestionType#TRUE_FALSE_MATRIX} the application
 * uses only A–D.
 */
@Entity
@Table(name = "question_options")
public class QuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_version_id", nullable = false)
    private Long questionVersionId;

    @Column(name = "option_key", nullable = false, length = 20)
    private String optionKey;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect = Boolean.FALSE;

    @Column(name = "position", nullable = false)
    private Integer position;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuestionOption() {
    }

    public QuestionOption(Long questionVersionId, String optionKey, String content,
                          Integer position, boolean correct) {
        this.questionVersionId = QuestionBank.requireNonNullId(questionVersionId, "questionVersionId");
        this.optionKey = QuestionBank.requireNonBlank("optionKey", optionKey);
        this.content = QuestionBank.requireNonBlank("content", content);
        this.position = requireNonNegativePosition(position);
        this.isCorrect = correct;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getQuestionVersionId() {
        return questionVersionId;
    }

    public void setQuestionVersionId(Long questionVersionId) {
        this.questionVersionId = questionVersionId;
    }

    public String getOptionKey() {
        return optionKey;
    }

    public void setOptionKey(String optionKey) {
        this.optionKey = optionKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public void setIsCorrect(Boolean isCorrect) {
        this.isCorrect = isCorrect;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QuestionOption other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return QuestionOption.class.hashCode();
    }

    @Override
    public String toString() {
        return "QuestionOption{" +
                "id=" + id +
                ", questionVersionId=" + questionVersionId +
                ", optionKey='" + optionKey + '\'' +
                ", isCorrect=" + isCorrect +
                ", position=" + position +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    private static Integer requireNonNegativePosition(Integer position) {
        if (position == null || position < 0) {
            throw new IllegalArgumentException("position must be >= 0");
        }
        return position;
    }
}
