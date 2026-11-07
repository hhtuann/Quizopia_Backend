package com.quizopia.backend.academic.repository;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link GradeLevel} — the global grade-level catalog
 * (school scope removed by the user-centric refactor, V24).
 */
public interface GradeLevelRepository extends JpaRepository<GradeLevel, Long> {

    Optional<GradeLevel> findByCodeIgnoreCase(String code);

    /** All grade levels, ordered for stable dropdown rendering. */
    List<GradeLevel> findAllByOrderBySortOrderAscNameAsc();
}
