package com.quizopia.backend.question.repository;

import com.quizopia.backend.question.domain.model.QuestionBank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link QuestionBank}.
 */
public interface QuestionBankRepository extends JpaRepository<QuestionBank, Long> {

    boolean existsByIdAndOwnerTeacherId(Long id, Long ownerTeacherId);

    boolean existsByOwnerTeacherIdAndCodeIgnoreCase(Long ownerTeacherId, String code);

    /**
     * Returns a page of banks owned by the given teacher, optionally filtered
     * by case-insensitive search (code or name) and subject.
     */
    @Query("SELECT qb FROM QuestionBank qb WHERE qb.ownerTeacherId = :ownerId "
            + "AND (COALESCE(:search, '') = '' "
            + "  OR LOWER(qb.code) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "  OR LOWER(qb.name) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "AND (:subjectId IS NULL OR qb.subjectId = :subjectId)")
    Page<QuestionBank> findOwnedByTeacher(@Param("ownerId") Long ownerId,
                                          @Param("search") String search,
                                          @Param("subjectId") Long subjectId,
                                          Pageable pageable);

    /**
     * Returns question counts grouped by bank id, for the given bank ids.
     * Used to avoid N+1 when building bank list responses.
     */
    @Query("SELECT q.questionBankId, COUNT(q) FROM Question q "
            + "WHERE q.questionBankId IN :bankIds GROUP BY q.questionBankId")
    List<Object[]> countQuestionsByBankIds(@Param("bankIds") List<Long> bankIds);
}
