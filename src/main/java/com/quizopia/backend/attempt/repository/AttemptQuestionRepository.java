package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.AttemptQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AttemptQuestion}. Questions are read ordered by
 * {@code displayOrder}; lookup by the composite key {@code (attemptId, id)}.
 * No EAGER collection — callers fetch questions explicitly per attempt.
 */
public interface AttemptQuestionRepository extends JpaRepository<AttemptQuestion, Long> {

    /** All questions of an attempt in stable display order. */
    List<AttemptQuestion> findByAttemptIdOrderByDisplayOrderAsc(Long attemptId);

    /** Lookup by composite key (attemptId, id) — used for ownership checks. */
    Optional<AttemptQuestion> findByIdAndAttemptId(Long id, Long attemptId);

    /** Lookup by (attemptId, examQuestionId) — used by autosave when only examQuestionId is given. */
    Optional<AttemptQuestion> findByAttemptIdAndExamQuestionId(Long attemptId, Long examQuestionId);
}
