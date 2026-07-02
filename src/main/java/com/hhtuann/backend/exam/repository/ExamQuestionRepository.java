package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {

    List<ExamQuestion> findAllByExamVersionId(Long examVersionId);

    List<ExamQuestion> findAllByExamSectionIdOrderByPositionAsc(Long examSectionId);

    List<ExamQuestion> findAllByExamSectionIdInOrderByExamSectionIdAscPositionAsc(List<Long> examSectionIds);

    void deleteAllByExamVersionId(Long examVersionId);
}
