package com.quizopia.backend.attempt.repository;

import com.quizopia.backend.attempt.domain.model.GradeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link GradeItem}. Read per grade in stable order. No grading
 * algorithm lives here.
 */
public interface GradeItemRepository extends JpaRepository<GradeItem, Long> {

    /** All grade items of a grade in stable order. */
    List<GradeItem> findByGradeIdOrderByIdAsc(Long gradeId);
}
