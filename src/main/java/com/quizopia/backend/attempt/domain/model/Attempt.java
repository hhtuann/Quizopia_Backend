package com.quizopia.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA entity mapping the V9 {@code attempts} table — one taking of an exam by a
 * student in a session.
 *
 * <p>All foreign keys are stored as writable scalar IDs (no associations, no
 * bi-directional graph); composite same-school integrity is enforced at the DB
 * level. No {@code @Version} column (the table has none); optimistic locking is
 * not used — submit takes a pessimistic lock via the repository. Lifecycle
 * transitions live in {@link #submit(Instant, String)} and
 * {@link #markGraded()}; the entity performs no authorization or clock lookup.
 *
 * <p>Submission invariant (V9 CHECK): {@code IN_PROGRESS} ⇒ submittedAt and
 * submissionIdempotencyKey both null; {@code SUBMITTED}/{@code GRADED} ⇒ both
 * non-null. The domain methods preserve this invariant atomically.
 */
@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;



    @Column(name = "exam_session_id", nullable = false)
    private Long examSessionId;

    /** Attempt owner user — the PRIMARY identity (NOT NULL; V12 + V16). */
    @Column(name = "user_id", nullable = false)
    private Long userId;



    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_saved_at")
    private Instant lastSavedAt;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "client_instance_id")
    private UUID clientInstanceId;

    @Column(name = "submission_idempotency_key", length = 100)
    private String submissionIdempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Attempt() {
    }

    private Attempt(Long examSessionId, Long userId,
                    Integer attemptNumber, Instant startedAt, Instant deadlineAt, UUID clientInstanceId) {
        this.examSessionId = Objects.requireNonNull(examSessionId, "examSessionId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.attemptNumber = requirePositive(attemptNumber, "attemptNumber");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.deadlineAt = requireDeadlineAfterStart(deadlineAt, startedAt);
        this.clientInstanceId = clientInstanceId;
    }

    /**
     * Factory starting a new IN_PROGRESS attempt owned by a platform USER.
     */
    public static Attempt start(Long examSessionId, Long userId,
                                Integer attemptNumber, Instant startedAt, Instant deadlineAt) {
        return new Attempt(examSessionId, userId,
                attemptNumber, startedAt, deadlineAt, null);
    }

    /** Factory overload accepting an optional clientInstanceId (null allowed). */
    public static Attempt start(Long examSessionId, Long userId,
                                Integer attemptNumber, Instant startedAt, Instant deadlineAt,
                                UUID clientInstanceId) {
        return new Attempt(examSessionId, userId,
                attemptNumber, startedAt, deadlineAt, clientInstanceId);
    }


    /**
     * Transitions IN_PROGRESS -> SUBMITTED, atomically setting the submission
     * timestamp and the submission idempotency key. The key must be non-blank.
     * Re-submission or submission of a non-IN_PROGRESS attempt is rejected.
     * Retry / cached-response logic does NOT live in the entity.
     */
    public void submit(Instant submittedAt, String submissionIdempotencyKey) {
        if (this.status != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("cannot submit attempt in status " + status);
        }
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        if (submissionIdempotencyKey == null || submissionIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("submissionIdempotencyKey must not be blank");
        }
        this.status = AttemptStatus.SUBMITTED;
        this.submittedAt = submittedAt;
        this.submissionIdempotencyKey = submissionIdempotencyKey;
    }

    /**
     * Day 8 grading transition SUBMITTED -> GRADED. The submission timestamp and
     * key remain set. Provided here so the persistence mapping can be exercised.
     */
    public void markGraded() {
        if (this.status != AttemptStatus.SUBMITTED) {
            throw new IllegalStateException("cannot grade attempt in status " + status);
        }
        this.status = AttemptStatus.GRADED;
    }

    /** Records the most recent autosave timestamp (updated by the autosave service). */
    public void touchLastSaved(Instant lastSavedAt) {
        this.lastSavedAt = Objects.requireNonNull(lastSavedAt, "lastSavedAt must not be null");
    }

    public Long getId() {
        return id;
    }



    public Long getExamSessionId() {
        return examSessionId;
    }

    public Long getUserId() {
        return userId;
    }



    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getLastSavedAt() {
        return lastSavedAt;
    }

    public UUID getClientInstanceId() {
        return clientInstanceId;
    }

    public String getSubmissionIdempotencyKey() {
        return submissionIdempotencyKey;
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
        if (!(o instanceof Attempt other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Attempt.class.hashCode();
    }

    @Override
    public String toString() {
        return "Attempt{"
                + "id=" + id
                + ", examSessionId=" + examSessionId
                + ", userId=" + userId
                + ", attemptNumber=" + attemptNumber
                + ", status=" + status
                + ", startedAt=" + startedAt
                + ", deadlineAt=" + deadlineAt
                + ", submittedAt=" + submittedAt
                + '}';
    }

    private static int requirePositive(Integer value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
        return value;
    }

    private static Instant requireDeadlineAfterStart(Instant deadlineAt, Instant startedAt) {
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        if (deadlineAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be >= startedAt");
        }
        return deadlineAt;
    }
}
