package com.quizopia.backend.attempt.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * JPA entity mapping the V35 {@code attempt_answer_history} table (P4-A3,
 * issue #82, ADR-008 invariant 7): exactly one row per SERVER-ACCEPTED
 * autosave sequence, written in the same database transaction as the
 * conditional UPSERT — never on stale/retried/duplicate saves.
 *
 * <p>The write path is the native {@code insertAccepted} in
 * {@code AttemptAnswerHistoryRepository}, called from
 * {@code AttemptAutosaveService} only when {@code upsertIfNewer} returned 1.
 * This entity serves reads (history API, verification tests).
 *
 * <p>Snapshot policy: {@code answerPayload} is the full canonical payload of
 * the accepted answer. Every question type in P4-A is a small structured
 * selection, so snapshots are bounded and audit-faithful; NO essay/free-text
 * type exists. When one is introduced, history MUST switch to hash-only
 * snapshots for those types (ADR-008 invariant 7: never per-keystroke essay
 * content).
 */
@Entity
@Table(name = "attempt_answer_history")
public class AttemptAnswerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "attempt_question_id", nullable = false)
    private Long attemptQuestionId;

    @Column(name = "accepted_sequence", nullable = false)
    private long acceptedSequence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer_payload", columnDefinition = "jsonb")
    private JsonNode answerPayload;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    protected AttemptAnswerHistory() {
        // JPA
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

    public long getAcceptedSequence() {
        return acceptedSequence;
    }

    public JsonNode getAnswerPayload() {
        return answerPayload;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}
