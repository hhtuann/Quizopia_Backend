package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.IdempotencyOperation;
import com.quizopia.backend.attempt.domain.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link IdempotencyRecord}.
 *
 * <p><b>This repository performs only data lookups — it does NOT decide
 * ownership.</b> The service (A3.2) must verify, before returning a cached
 * response as a valid same-key retry, that:
 * <ol>
 *   <li>{@code record.userId} is the caller / owner of the attempt, and</li>
 *   <li>{@code record.idempotencyKey} matches {@code Attempt.submissionIdempotencyKey}.</li>
 * </ol>
 * No DB constraint links these across tables (A2 review LOW-1/LOW-2); adding one
 * is explicitly out of scope for this checkpoint.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /** Lookup by (user, operation, key) — the idempotency scope. */
    Optional<IdempotencyRecord> findByUserIdAndOperationAndIdempotencyKey(Long userId,
                                                                           IdempotencyOperation operation,
                                                                           String idempotencyKey);

    /** Lookup by (attempt, operation) — at most one cached submit per attempt. */
    Optional<IdempotencyRecord> findByAttemptIdAndOperation(Long attemptId, IdempotencyOperation operation);
}
