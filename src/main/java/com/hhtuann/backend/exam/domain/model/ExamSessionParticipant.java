package com.hhtuann.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code exam_session_participants} table. A participant
 * is a student explicitly added to a session. The {@code version} column is a
 * JPA optimistic-lock version.
 *
 * <p>Status: {@code ELIGIBLE} (default, may start attempts) or {@code BLOCKED}
 * (disabled). The DB CHECK {@code chk_exam_session_participants_blocked} enforces
 * the status ↔ blockedAt invariant at the database level.
 */
@Entity
@Table(name = "exam_session_participants")
public class ExamSessionParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    @Column(name = "student_profile_id", nullable = false)
    private Long studentProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExamSessionParticipantStatus status = ExamSessionParticipantStatus.ELIGIBLE;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @CreationTimestamp
    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;

    @Column(name = "blocked_at")
    private Instant blockedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    protected ExamSessionParticipant() {
    }

    public ExamSessionParticipant(Long schoolId, Long examSessionId, Long studentProfileId, Long addedBy) {
        this.schoolId = ExamPurpose.requireNonNullId(schoolId, "schoolId");
        this.examSessionId = ExamPurpose.requireNonNullId(examSessionId, "examSessionId");
        this.studentProfileId = ExamPurpose.requireNonNullId(studentProfileId, "studentProfileId");
        this.addedBy = ExamPurpose.requireNonNullId(addedBy, "addedBy");
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public Long getStudentProfileId() {
        return studentProfileId;
    }

    public ExamSessionParticipantStatus getStatus() {
        return status;
    }

    public Long getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }

    public Integer getVersion() {
        return version;
    }

    /**
     * Blocks this participant (sets status BLOCKED + blockedAt). The DB CHECK
     * enforces that BLOCKED requires blockedAt not null.
     */
    public void block(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        this.status = ExamSessionParticipantStatus.BLOCKED;
        this.blockedAt = now;
    }

    /**
     * Unblocks this participant (sets status ELIGIBLE + clears blockedAt). The
     * DB CHECK enforces that ELIGIBLE requires blockedAt null.
     */
    public void unblock() {
        this.status = ExamSessionParticipantStatus.ELIGIBLE;
        this.blockedAt = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamSessionParticipant other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamSessionParticipant.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamSessionParticipant{" +
                "id=" + id +
                ", schoolId=" + schoolId +
                ", examSessionId=" + examSessionId +
                ", studentProfileId=" + studentProfileId +
                ", status=" + status +
                ", addedAt=" + addedAt +
                '}';
    }
}
