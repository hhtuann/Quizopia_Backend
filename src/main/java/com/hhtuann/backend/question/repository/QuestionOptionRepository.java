package com.hhtuann.backend.question.repository;

import com.hhtuann.backend.question.domain.model.QuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link QuestionOption}.
 */
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {

    List<QuestionOption> findByQuestionVersionId(Long questionVersionId);
}
