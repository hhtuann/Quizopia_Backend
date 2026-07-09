package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link GradeLevel}.
 */
public interface GradeLevelRepository extends JpaRepository<GradeLevel, Long> {

    Optional<GradeLevel> findBySchoolIdAndCodeIgnoreCase(Long schoolId, String code);

    /** A grade level within a specific school (used to validate subject creation). */
    Optional<GradeLevel> findBySchoolIdAndId(Long schoolId, Long id);

    /** All grade levels in a school, ordered for stable dropdown rendering. */
    List<GradeLevel> findBySchoolIdOrderBySortOrderAscNameAsc(Long schoolId);

    /** All grade levels across ALL schools (admin cross-school oversight). */
    List<GradeLevel> findAllByOrderBySchoolIdAscSortOrderAscNameAsc();
}
