package com.quizopia.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping the {@code exam_question_options} table — a snapshot of a
 * question's choice/TF option. Correctness is stored in {@code isCorrect}.
 * The {@code toString()} excludes {@code isCorrect} to avoid leaking answers.
 */
@Entity
@Table(name = "exam_question_options")
public class ExamQuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_question_id", nullable = false)
    private Long examQuestionId;

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

    protected ExamQuestionOption() {
    }

    public ExamQuestionOption(Long examQuestionId, String optionKey, String content,
                              Boolean isCorrect, Integer position) {
        this.examQuestionId = ExamPurpose.requireNonNullId(examQuestionId, "examQuestionId");
        this.optionKey = ExamPurpose.requireNonBlank("optionKey", optionKey);
        this.content = ExamPurpose.requireNonBlank("content", content);
        this.isCorrect = isCorrect != null ? isCorrect : Boolean.FALSE;
        this.position = requireNonNegative(position, "position");
    }

    public Long getId() {
        return id;
    }

    public Long getExamQuestionId() {
        return examQuestionId;
    }

    public String getOptionKey() {
        return optionKey;
    }

    public String getContent() {
        return content;
    }

    public Boolean getIsCorrect() {
        return isCorrect;
    }

    public Integer getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamQuestionOption other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamQuestionOption.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamQuestionOption{" +
                "id=" + id +
                ", examQuestionId=" + examQuestionId +
                ", optionKey='" + optionKey + '\'' +
                ", position=" + position +
                ", createdAt=" + createdAt +
                '}';
    }

    private static Integer requireNonNegative(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
        return value;
    }
}
