package com.quizopia.backend.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code question_banks} table. A bank is owned
 * directly by its creating user (user-centric model, V24/V25); the
 * optional {@code subjectId} references the global academic catalog.
 */
@Entity
@Table(name = "question_banks")
public class QuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Creating/owning user (NOT NULL). */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserUserId;

    /** Optional global-catalog subject metadata. */
    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    private QuestionBankVisibility visibility = QuestionBankVisibility.PRIVATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private QuestionBankStatus status = QuestionBankStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected QuestionBank() {
    }

    public QuestionBank(Long createdByUserUserId, Long subjectId, String code, String name) {
        this.createdByUserUserId = requireNonNullId(createdByUserUserId, "createdByUserUserId");
        this.subjectId = subjectId;
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreatedByUserUserId() {
        return createdByUserUserId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public QuestionBankVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(QuestionBankVisibility visibility) {
        this.visibility = visibility;
    }

    public QuestionBankStatus getStatus() {
        return status;
    }

    public void setStatus(QuestionBankStatus status) {
        this.status = status;
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
        if (!(o instanceof QuestionBank other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return QuestionBank.class.hashCode();
    }

    @Override
    public String toString() {
        return "QuestionBank{" +
                "id=" + id +
                ", createdByUserUserId=" + createdByUserUserId +
                ", subjectId=" + subjectId +
                ", code='" + code + '\'' +
                ", visibility=" + visibility +
                ", status=" + status +
                '}';
    }

    static Long requireNonNullId(Long value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    static String requireNonBlank(String fieldName, String value) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
