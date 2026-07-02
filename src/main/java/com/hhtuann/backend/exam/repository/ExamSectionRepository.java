package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.ExamSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamSectionRepository extends JpaRepository<ExamSection, Long> {

    List<ExamSection> findAllByExamVersionIdOrderByPositionAsc(Long examVersionId);

    void deleteAllByExamVersionId(Long examVersionId);
}
