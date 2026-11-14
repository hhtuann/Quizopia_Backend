package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link Attempt}. The attempt-number allocation uses
 * {@link #findMaxAttemptNumber} (MAX + 1) — never {@code count()+1} — and is
 * computed under the participant pessimistic lock by the service.
 */
public interface AttemptRepository extends JpaRepository<Attempt, Long> {

    /** Pessimistic-write lock on a single attempt (submit/autosave critical section). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attempt a WHERE a.id = :id")
    Optional<Attempt> findByIdForUpdate(@Param("id") Long id);

    /**
     * The single IN_PROGRESS attempt for (session, user), if any. The DB
     * partial unique index (V16: exam_session_id, user_id) guarantees at
     * most one.
     */
    Optional<Attempt> findByExamSessionIdAndUserIdAndStatus(Long examSessionId,
                                                            Long userId,
                                                            AttemptStatus status);

    /** All attempts for a (session, user) — any status. Day 8 BEST / result count. */
    List<Attempt> findByExamSessionIdAndUserId(Long examSessionId, Long userId);

    /**
     * The highest attempt number used by (session, user); empty when none.
     * The service allocates {@code next = max.orElse(0) + 1}.
     */
    @Query("SELECT MAX(a.attemptNumber) FROM Attempt a "
            + "WHERE a.examSessionId = :sessionId AND a.userId = :userId")
    Optional<Integer> findMaxAttemptNumber(@Param("sessionId") Long sessionId,
                                            @Param("userId") Long userId);

    /** Number of attempts in a session for a given status (e.g. active count). */
    long countByExamSessionIdAndStatus(Long examSessionId, AttemptStatus status);

    /** Per-user attempt history, newest first (user-based, issue #4 P0-1). */
    Page<Attempt> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * IDs of IN_PROGRESS attempts whose deadline has passed, oldest deadline
     * first — the work-list for the timeout sweeper. Returns IDs only (the
     * sweeper locks + finalizes each one individually via
     * {@link #findByIdForUpdate} so no long-held locks).
     */
    @Query("SELECT a.id FROM Attempt a WHERE a.status = :status AND a.deadlineAt < :now "
            + "ORDER BY a.deadlineAt ASC")
    List<Long> findExpiredInProgressIds(@Param("status") AttemptStatus status, @Param("now") Instant now);

    /**
     * All attempts of a session in stable id order — the teacher
 *     activity-monitoring picker (P4-A #83): includes IN_PROGRESS attempts,
     * unlike the graded best-results report.
     */
    List<Attempt> findByExamSessionIdOrderByIdAsc(Long examSessionId);
}
