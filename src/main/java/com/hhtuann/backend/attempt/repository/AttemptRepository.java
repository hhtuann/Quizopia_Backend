package com.hhtuann.backend.attempt.repository;

import com.hhtuann.backend.attempt.domain.model.Attempt;
import com.hhtuann.backend.attempt.domain.model.AttemptStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
     * The single IN_PROGRESS attempt for (session, student), if any. The DB
     * partial unique index guarantees at most one.
     */
    Optional<Attempt> findByExamSessionIdAndStudentProfileIdAndStatus(Long examSessionId,
                                                                       Long studentProfileId,
                                                                       AttemptStatus status);

    /** All attempts for a (session, student) — any status. Day 8 BEST / result count. */
    List<Attempt> findByExamSessionIdAndStudentProfileId(Long examSessionId, Long studentProfileId);

    /**
     * The highest attempt number used by (session, student); empty when none.
     * The service allocates {@code next = max.orElse(0) + 1}.
     */
    @Query("SELECT MAX(a.attemptNumber) FROM Attempt a "
            + "WHERE a.examSessionId = :sessionId AND a.studentProfileId = :studentId")
    Optional<Integer> findMaxAttemptNumber(@Param("sessionId") Long sessionId,
                                            @Param("studentId") Long studentId);

    /** Number of attempts in a session for a given status (e.g. active count). */
    long countByExamSessionIdAndStatus(Long examSessionId, AttemptStatus status);

    /** Per-student attempt history, newest first. */
    Page<Attempt> findByStudentProfileIdOrderByCreatedAtDesc(Long studentProfileId, Pageable pageable);
}
