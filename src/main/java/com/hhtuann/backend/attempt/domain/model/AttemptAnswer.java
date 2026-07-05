package com.hhtuann.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the V9 {@code attempt_answers} table — the autosaved
 * answer for one (attempt, attempt_question) pair.
 *
 * <p>This entity models the table for read/round-trip use; the actual autosave
 * write path is the native atomic UPSERT in {@code AttemptAnswerRepository}
 * ({@code ON CONFLICT ... DO UPDATE WHERE sequence_number < EXCLUDED.sequence_number}),
 * which is what decides {@code saved_at} and enforces the strictly-greater
 * sequence guard. The entity is therefore not used to perform the guarded
 * UPSERT (entity {@code save()} cannot express the conditional update).
 *
 * <p>{@code sequence_number} is {@code >= 1}. {@code answer_payload} is a
 * nullable JSONB object (NULL = no answer / cleared). Both foreign keys are
 * scalar IDs (the composite ownership FK is enforced at the DB level).
 */
@Entity
@Table(name = "attempt_answers")
public class AttemptAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "attempt_question_id", nullable = false)
    private Long attemptQuestionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_payload", columnDefinition = "jsonb")
    private JsonNode answerPayload;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    protected AttemptAnswer() {
    }

    /**
     * Constructs an answer row. Used for test/round-trip persistence; the live
     * autosave path uses the repository UPSERT. {@code sequenceNumber} must be
     * {@code >= 1}; {@code savedAt} is provided by the caller (the UPSERT uses
     * {@code CURRENT_TIMESTAMP}).
     */
    public AttemptAnswer(Long attemptId, Long attemptQuestionId, JsonNode answerPayload,
                         long sequenceNumber, Instant savedAt) {
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.attemptQuestionId = Objects.requireNonNull(attemptQuestionId, "attemptQuestionId must not be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        this.sequenceNumber = sequenceNumber;
        this.answerPayload = answerPayload;
        this.savedAt = Objects.requireNonNull(savedAt, "savedAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public Long getAttemptQuestionId() {
        return attemptQuestionId;
    }

    public JsonNode getAnswerPayload() {
        return answerPayload;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getSavedAt() {
        return savedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AttemptAnswer other)) {
            return false;
        }
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return AttemptAnswer.class.hashCode();
    }

    @Override
    public String toString() {
        return "AttemptAnswer{"
                + "id=" + id
                + ", attemptId=" + attemptId
                + ", attemptQuestionId=" + attemptQuestionId
                + ", sequenceNumber=" + sequenceNumber
                + ", savedAt=" + savedAt
                + '}';
    }
}
