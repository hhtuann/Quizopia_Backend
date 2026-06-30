package com.hhtuann.backend.question.repository;

import com.hhtuann.backend.question.domain.model.QuestionVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link QuestionVersion}.
 */
public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, Long> {

    Optional<QuestionVersion> findByQuestionIdAndVersionNumber(Long questionId, Integer versionNumber);
}
