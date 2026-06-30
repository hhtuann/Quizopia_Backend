package com.hhtuann.backend.question.repository;

import com.hhtuann.backend.question.domain.model.Question;
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
}
