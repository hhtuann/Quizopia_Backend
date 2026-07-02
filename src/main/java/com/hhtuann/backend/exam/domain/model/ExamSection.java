package com.hhtuann.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping the {@code exam_sections} table. A section is a mandatory
 * container for exam questions within a version. Section position is unique
 * within a version; question position is local within a section.
 */
@Entity
@Table(name = "exam_sections")
public class ExamSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_version_id", nullable = false)
    private Long examVersionId;

    @Column(name = "code", length = 80)
    private String code;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "position", nullable = false)
    private Integer position = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ExamSection() {
    }

    public ExamSection(Long examVersionId, String title, Integer position) {
        this.examVersionId = ExamPurpose.requireNonNullId(examVersionId, "examVersionId");
        this.title = ExamPurpose.requireNonBlank("title", title);
        this.position = requireNonNegative(position, "position");
    }

    public Long getId() {
        return id;
    }

    public Long getExamVersionId() {
        return examVersionId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
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
        if (!(o instanceof ExamSection other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamSection.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamSection{" +
                "id=" + id +
                ", examVersionId=" + examVersionId +
                ", code='" + code + '\'' +
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
