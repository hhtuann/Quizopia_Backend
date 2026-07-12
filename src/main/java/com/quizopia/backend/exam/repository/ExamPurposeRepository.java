package com.quizopia.backend.exam.repository;

import com.quizopia.backend.exam.domain.model.ExamPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamPurposeRepository extends JpaRepository<ExamPurpose, Long> {

    List<ExamPurpose> findAllBySchoolIdOrderByPositionAsc(Long schoolId);

    boolean existsBySchoolIdAndCodeIgnoreCase(Long schoolId, String code);
}
