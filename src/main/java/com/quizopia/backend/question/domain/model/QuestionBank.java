package com.quizopia.backend.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code question_banks} table. A bank belongs to a
 * subject and an owner teacher, both in the same school (enforced at DB level
 * by two composite foreign keys on {@code (school_id, subject_id)} and
 * {@code (school_id, owner_teacher_id)}).
 */
@Entity
@Table(name = "question_banks")
public class QuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "owner_teacher_id", nullable = false)
    private Long ownerTeacherId;

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

    public QuestionBank(Long schoolId, Long subjectId, Long ownerTeacherId, String code, String name) {
        this.schoolId = requireNonNullId(schoolId, "schoolId");
        this.subjectId = requireNonNullId(subjectId, "subjectId");
        this.ownerTeacherId = requireNonNullId(ownerTeacherId, "ownerTeacherId");
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(Long schoolId) {
        this.schoolId = schoolId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(Long subjectId) {
        this.subjectId = subjectId;
    }

    public Long getOwnerTeacherId() {
        return ownerTeacherId;
    }

    public void setOwnerTeacherId(Long ownerTeacherId) {
        this.ownerTeacherId = ownerTeacherId;
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
                ", schoolId=" + schoolId +
                ", subjectId=" + subjectId +
                ", ownerTeacherId=" + ownerTeacherId +
                ", code='" + code + '\'' +
                ", visibility=" + visibility +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
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
