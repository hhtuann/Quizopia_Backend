package com.quizopia.backend.question.repository;

import com.quizopia.backend.question.domain.model.QuestionVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link QuestionVersion}.
 */
public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, Long> {

    Optional<QuestionVersion> findByQuestionIdAndVersionNumber(Long questionId, Integer versionNumber);

    /**
     * Batch load of all versions for the given question ids. Used by the exam
     * composition snapshot to resolve each question's current version in a
     * single query (no per-question lookup). The caller picks the current
     * version (by {@code Question.currentVersionNumber}) in memory.
     */
    List<QuestionVersion> findAllByQuestionIdIn(List<Long> questionIds);
}
