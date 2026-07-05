package com.hhtuann.backend.attempt.repository;

import com.hhtuann.backend.attempt.domain.model.Grade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Grade}. Lookup by attempt (one grade per attempt, DB
 * unique). No grading service is created here — grading logic is Day 8.
 */
public interface GradeRepository extends JpaRepository<Grade, Long> {

    /** The single grade for an attempt, if any (DB unique on attempt_id). */
    Optional<Grade> findByAttemptId(Long attemptId);

    /** Batch lookup — all grades for the given attempt ids (Day 8 BEST query / results). */
    List<Grade> findAllByAttemptIdIn(List<Long> attemptIds);
}
