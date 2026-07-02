package com.hhtuann.backend.exam.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code exam_sessions} table. A session is a sitting
 * that pins one PUBLISHED exam version. The {@code version} column is a JPA
 * optimistic-lock version.
 *
 * <p>State machine: DRAFT → SCHEDULED → OPEN → CLOSED; DRAFT/SCHEDULED → CANCELLED.
 * The DB CHECK {@code chk_exam_sessions_state_timestamps} enforces the
 * status ↔ timestamps invariant at the database level.
 */
@Entity
@Table(name = "exam_sessions")
public class ExamSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "exam_version_id", nullable = false)
    private Long examVersionId;

    @Column(name = "owner_teacher_id", nullable = false)
    private Long ownerTeacherId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ExamSessionStatus status = ExamSessionStatus.DRAFT;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 1;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamSession() {
    }

    public ExamSession(Long schoolId, Long examVersionId, Long ownerTeacherId,
                       String code, String title, Instant startsAt, Instant endsAt,
                       Integer maxAttempts, Long createdBy) {
        this.schoolId = ExamPurpose.requireNonNullId(schoolId, "schoolId");
        this.examVersionId = ExamPurpose.requireNonNullId(examVersionId, "examVersionId");
        this.ownerTeacherId = ExamPurpose.requireNonNullId(ownerTeacherId, "ownerTeacherId");
        this.code = ExamPurpose.requireNonBlank("code", code);
        this.title = ExamPurpose.requireNonBlank("title", title);
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt must not be null");
        this.endsAt = Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");
        this.createdBy = ExamPurpose.requireNonNullId(createdBy, "createdBy");
    }

    public Long getId() {
        return id;
    }

    public Long getSchoolId() {
        return schoolId;
    }

    public Long getExamVersionId() {
        return examVersionId;
    }

    public Long getOwnerTeacherId() {
        return ownerTeacherId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public ExamSessionStatus getStatus() {
        return status;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
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
     * Transition DRAFT → SCHEDULED. Does not validate time window here (service
     * responsibility), but the DB CHECK validates the timestamp invariant.
     */
    public void schedule() {
        this.status = ExamSessionStatus.SCHEDULED;
    }

    /**
     * Transition SCHEDULED → OPEN. Sets {@code openedAt}. Time-window validation
     * (startsAt ≤ now ≤ endsAt) is the service's responsibility.
     */
    public void open(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        this.status = ExamSessionStatus.OPEN;
        this.openedAt = now;
    }

    /**
     * Transition OPEN → CLOSED. Sets {@code closedAt}.
     */
    public void close(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        this.status = ExamSessionStatus.CLOSED;
        this.closedAt = now;
    }

    /**
     * Transition DRAFT/SCHEDULED → CANCELLED. Does not set timestamps (the DB
     * invariant requires both null for CANCELLED).
     */
    public void cancel() {
        this.status = ExamSessionStatus.CANCELLED;
    }

    /**
     * Updates session configuration (only allowed in DRAFT/SCHEDULED).
     */
    public void updateConfig(String title, Instant startsAt, Instant endsAt, Integer maxAttempts) {
        if (title != null) {
            this.title = title;
        }
        if (startsAt != null && endsAt != null) {
            if (!endsAt.isAfter(startsAt)) {
                throw new IllegalArgumentException("endsAt must be after startsAt");
            }
            this.startsAt = startsAt;
            this.endsAt = endsAt;
        }
        if (maxAttempts != null) {
            this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExamSession other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return ExamSession.class.hashCode();
    }

    @Override
    public String toString() {
        return "ExamSession{" +
                "id=" + id +
                ", schoolId=" + schoolId +
                ", examVersionId=" + examVersionId +
                ", ownerTeacherId=" + ownerTeacherId +
                ", code='" + code + '\'' +
                ", status=" + status +
                ", startsAt=" + startsAt +
                ", endsAt=" + endsAt +
                ", maxAttempts=" + maxAttempts +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }

    private static Integer requirePositive(Integer value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return value;
    }
}
