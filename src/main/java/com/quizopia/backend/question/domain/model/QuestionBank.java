package com.quizopia.backend.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code question_banks} table. A bank is owned by a
 * workspace and a creating user (public platform foundation, ADR-001 §2.2).
 * The legacy {@code schoolId}/{@code subjectId}/{@code ownerTeacherId} columns
 * remain as nullable provenance for school-era rows; the DB composite FKs
 * still enforce same-school for those rows.
 */
@Entity
@Table(name = "question_banks")
public class QuestionBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning workspace (NOT NULL). */
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Creating/owning user (NOT NULL). */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserUserId;

    /** Legacy provenance; null for banks created outside a school context. */
    @Column(name = "school_id")
    private Long schoolId;

    /** Legacy/academic provenance; optional for personal-workspace banks. */
    @Column(name = "subject_id")
    private Long subjectId;

    /** Legacy provenance (teacher_profiles.id); null for personal banks. */
    @Column(name = "owner_teacher_id")
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

    public QuestionBank(Long workspaceId, Long createdByUserUserId, Long schoolId, Long subjectId,
                        Long ownerTeacherId, String code, String name) {
        this.workspaceId = requireNonNullId(workspaceId, "workspaceId");
        this.createdByUserUserId = requireNonNullId(createdByUserUserId, "createdByUserUserId");
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
    }

    /**
     * Legacy-shape constructor (transitional, ADR-001 §2.2): workspace and
     * creator are resolved by the V11 BEFORE INSERT trigger from the school /
     * owner-teacher provenance. Intended for legacy fixtures and transitional
     * writers; application services must use the explicit constructor.
     */
    public QuestionBank(Long schoolId, Long subjectId, Long ownerTeacherId, String code, String name) {
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = requireNonBlank("code", code);
        this.name = requireNonBlank("name", name);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getCreatedByUserUserId() {
        return createdByUserUserId;
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
