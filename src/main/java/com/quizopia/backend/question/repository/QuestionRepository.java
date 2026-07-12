package com.quizopia.backend.question.repository;

import com.quizopia.backend.question.domain.model.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link Question}.
 */
public interface QuestionRepository extends JpaRepository<Question, Long> {

    /**
     * Returns the lower-cased codes of all questions in a bank. Used by the
     * Excel import flow to detect duplicates against existing bank content in
     * a single query (no N+1).
     */
    @Query("select lower(q.code) from Question q where q.questionBankId = :bankId")
    List<String> findLowerCodesByBankId(@Param("bankId") Long bankId);

    /** Case-insensitive code-exists check within a bank (for auto-generated codes). */
    boolean existsByQuestionBankIdAndCodeIgnoreCase(Long questionBankId, String code);

    /** A question within a specific bank (ownership validation). */
    java.util.Optional<Question> findByQuestionBankIdAndId(Long questionBankId, Long id);
}
