package com.quizopia.backend.exam.repository;

import com.quizopia.backend.exam.domain.model.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {

    List<ExamQuestion> findAllByExamId(Long examId);

    List<ExamQuestion> findAllByExamSectionIdOrderByPositionAsc(Long examSectionId);

    List<ExamQuestion> findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(List<Long> examSectionIds);

    void deleteAllByExamId(Long examId);

    /** Question counts grouped by exam id (list views, no N+1). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT q.examId, COUNT(q) FROM ExamQuestion q WHERE q.examId IN :examIds GROUP BY q.examId")
    List<Object[]> countByExamIdsIn(@org.springframework.data.repository.query.Param("examIds") List<Long> examIds);
}
