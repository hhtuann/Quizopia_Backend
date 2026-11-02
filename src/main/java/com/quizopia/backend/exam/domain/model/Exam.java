package com.quizopia.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping the {@code exams} table. An exam is the stable identity
 * of a test with a single immutable content set after publish (Phase 1 Task B
 * redesign, ADR-001 §2.4). The {@code version} column is a JPA optimistic-lock
 * version (NOT exam content versioning).
 *
 * <p>Ownership: {@code workspaceId} + {@code createdByUserUserId} (public
 * platform foundation, ADR-001 §2.2). Legacy {@code schoolId}/{@code subjectId}/
 * {@code ownerTeacherId} remain as nullable provenance; the DB composite FKs
 * still enforce same-school for school-era rows.
 */
@Entity
@Table(name = "exams")
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning workspace (NOT NULL). */
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** Creating/owning user (NOT NULL). */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserUserId;

    /** Legacy provenance; null for exams created outside a school context. */
    @Column(name = "school_id")
    private Long schoolId;

    /** Legacy/academic provenance; optional for personal-workspace exams. */
    @Column(name = "subject_id")
    private Long subjectId;

    /** Legacy provenance (teacher_profiles.id); null for personal exams. */
    @Column(name = "owner_teacher_id")
    private Long ownerTeacherId;

    @Column(name = "purpose_id")
    private Long purposeId;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "current_version_number", nullable = false)
    private Integer currentVersionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExamStatus status = ExamStatus.DRAFT;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Exam() {
    }

    public Exam(Long workspaceId, Long createdByUserUserId, Long schoolId, Long subjectId,
                Long ownerTeacherId, String code, String title) {
        this.workspaceId = ExamPurpose.requireNonNullId(workspaceId, "workspaceId");
        this.createdByUserUserId = ExamPurpose.requireNonNullId(createdByUserUserId, "createdByUserUserId");
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
    }

    /**
     * Legacy-shape constructor (transitional, ADR-001 §2.2): workspace and
     * creator are resolved by the V11 BEFORE INSERT trigger from the school /
     * owner-teacher provenance. Intended for legacy fixtures and transitional
     * writers; application services must use the explicit constructor.
     */
    public Exam(Long schoolId, Long subjectId, Long ownerTeacherId, String code, String title) {
        this.schoolId = schoolId;
        this.subjectId = subjectId;
        this.ownerTeacherId = ownerTeacherId;
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
    }

    public Long getId() {
        return id;
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

    public Long getSubjectId() {
        return subjectId;
    }

    public Long getOwnerTeacherId() {
        return ownerTeacherId;
    }

    public Long getPurposeId() {
        return purposeId;
    }

    public void setPurposeId(Long purposeId) {
        this.purposeId = purposeId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getCurrentVersionNumber() {
        return currentVersionNumber;
    }

    public ExamStatus getStatus() {
        return status;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Marks the exam as READY (at least one version has been published).
     * One-way transition: never reverts to DRAFT.
     */
    public void markReady() {
        this.status = ExamStatus.READY;
    }

    /**
     * Advances {@code currentVersionNumber} to the given value if it is higher
     * than the current value. {@code currentVersionNumber} tracks the highest
     * version number ever created (not necessarily the latest published).
     */
    public void advanceCurrentVersion(int versionNumber) {
        if (versionNumber > this.currentVersionNumber) {
            this.currentVersionNumber = versionNumber;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Exam other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Exam.class.hashCode();
    }

    @Override
    public String toString() {
        return "Exam{" +
                "id=" + id +
                ", schoolId=" + schoolId +
                ", subjectId=" + subjectId +
                ", ownerTeacherId=" + ownerTeacherId +
                ", code='" + code + '\'' +
                ", currentVersionNumber=" + currentVersionNumber +
                ", status=" + status +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
