package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.AttemptAnswerHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * P4-A3 (issue #82): accepted-answer history. The ONLY write path is
 * {@link #insertAccepted}, called from {@code AttemptAutosaveService.saveAnswer}
 * immediately after {@code upsertIfNewer} returned 1 — same transaction, so a
 * history failure rolls the autosave back and a stale save writes nothing.
 *
 * <p>UNIQUE (attempt_id, attempt_question_id, accepted_sequence) is the
 * atomicity witness: under the attempt-row lock a sequence is accepted at
 * most once, so a constraint violation here means a genuine integrity
 * breach and MUST abort the transaction (never an ON CONFLICT swallow).
 */
public interface AttemptAnswerHistoryRepository extends JpaRepository<AttemptAnswerHistory, Long> {

    /**
     * Plain INSERT (no upsert): the strictly-greater sequence guard has
     * already accepted this save, so the (attempt, question, sequence) key is
     * new. Any constraint failure propagates and rolls back the autosave.
     */
    @Modifying
    @Query(value = "INSERT INTO attempt_answer_history "
            + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) "
            + "VALUES (:attemptId, :attemptQuestionId, :acceptedSequence, CAST(:payloadJson AS jsonb), :acceptedAt)",
            nativeQuery = true)
    int insertAccepted(
            @Param("attemptId") long attemptId,
            @Param("attemptQuestionId") long attemptQuestionId,
            @Param("acceptedSequence") long acceptedSequence,
            @Param("payloadJson") String payloadJson,
            @Param("acceptedAt") java.sql.Timestamp acceptedAt);

    /** History of one attempt question, chronological by acceptance. */
    List<AttemptAnswerHistory> findByAttemptIdAndAttemptQuestionIdOrderByAcceptedAtAscIdAsc(
            Long attemptId, Long attemptQuestionId);

    /** Full history of an attempt, chronological by acceptance. */
    List<AttemptAnswerHistory> findByAttemptIdOrderByAcceptedAtAscIdAsc(Long attemptId);

    long countByAttemptId(Long attemptId);
}
