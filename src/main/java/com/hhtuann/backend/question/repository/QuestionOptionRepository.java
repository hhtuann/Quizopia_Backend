package com.hhtuann.backend.question.repository;

import com.hhtuann.backend.question.domain.model.QuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link QuestionOption}.
 */
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {

    List<QuestionOption> findByQuestionVersionId(Long questionVersionId);

    /**
     * Batch load of options for the given question-version ids, ordered by
     * version id then position. Used by the exam composition snapshot to copy
     * option snapshots for all pinned source versions in one query.
     */
    List<QuestionOption> findAllByQuestionVersionIdInOrderByQuestionVersionIdAscPositionAsc(
            List<Long> questionVersionIds);
}
