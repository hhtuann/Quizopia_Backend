package com.quizopia.backend.exam.repository;

import com.quizopia.backend.exam.domain.model.ExamQuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamQuestionOptionRepository extends JpaRepository<ExamQuestionOption, Long> {

    List<ExamQuestionOption> findAllByExamQuestionIdOrderByPositionAsc(Long examQuestionId);

    List<ExamQuestionOption> findAllByExamQuestionIdInOrderByExamQuestionIdAscPositionAsc(List<Long> examQuestionIds);

    void deleteAllByExamQuestionIdIn(List<Long> examQuestionIds);
}
