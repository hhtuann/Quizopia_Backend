package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.AttemptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AttemptAnswer}.
 *
 * <p>The autosave write path is the native atomic UPSERT
 * {@link #upsertIfNewer}, which is the only thing that decides {@code saved_at}
 * and enforces the strictly-greater sequence guard — entity {@code save()}
 * cannot express the conditional update, so it is NOT used to simulate the
 * guard. {@code payloadJson} is the answer payload serialized to JSON text
 * (null allowed); the caller serializes the {@code JsonNode}. The int return is
 * {@code 0} when the incoming sequence is not newer (stale / ignored) and
 * {@code 1} when accepted (inserted or updated).
 */
public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, Long> {

    /** All answers of an attempt. */
    List<AttemptAnswer> findByAttemptId(Long attemptId);

    /** The single answer for (attempt, attempt_question), if any. */
    Optional<AttemptAnswer> findByAttemptIdAndAttemptQuestionId(Long attemptId, Long attemptQuestionId);

    /**
     * Atomic conditional UPSERT. Inserts a new answer, or updates the existing
     * (attempt, attempt_question) row ONLY when its sequence_number is strictly
     * less than the incoming sequence. Returns the affected row count (0 = stale,
     * 1 = accepted). {@code sequence} must be {@code >= 1} (also enforced by the
     * DB CHECK). The composite ownership FK rejects a cross-attempt question.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number, saved_at) "
            + "VALUES (:attemptId, :attemptQuestionId, CAST(:payloadJson AS jsonb), :sequence, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (attempt_id, attempt_question_id) DO UPDATE SET "
            + "answer_payload = EXCLUDED.answer_payload, "
            + "sequence_number = EXCLUDED.sequence_number, "
            + "saved_at = CURRENT_TIMESTAMP "
            + "WHERE attempt_answers.sequence_number < EXCLUDED.sequence_number",
            nativeQuery = true)
    int upsertIfNewer(@Param("attemptId") long attemptId,
                      @Param("attemptQuestionId") long attemptQuestionId,
                      @Param("payloadJson") String payloadJson,
                      @Param("sequence") long sequence);
}
