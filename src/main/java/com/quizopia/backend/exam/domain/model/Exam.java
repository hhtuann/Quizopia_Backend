package com.quizopia.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * JPA entity mapping the {@code exams} table. An exam is the stable identity of
 * a test; its content lives in {@link ExamVersion} rows. The {@code version}
 * column is a JPA optimistic-lock version (NOT an exam content version — that is
 * {@code currentVersionNumber}).
 *
 * <p>Composite FKs enforce same-school for subject, owner teacher, and purpose.
 * Code uniqueness is owner-scoped {@code (owner_teacher_id, LOWER(code))}.
 */
@Entity
@Table(name = "exams")
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "owner_teacher_id", nullable = false)
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

    public Exam(Long schoolId, Long subjectId, Long ownerTeacherId, String code, String title) {
        this.schoolId = ExamPurpose.requireNonNullId(schoolId, "schoolId");
        this.subjectId = ExamPurpose.requireNonNullId(subjectId, "subjectId");
        this.ownerTeacherId = ExamPurpose.requireNonNullId(ownerTeacherId, "ownerTeacherId");
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
    }

    public Long getId() {
        return id;
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
