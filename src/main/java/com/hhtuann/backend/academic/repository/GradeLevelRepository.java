package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Deletes a grade level only when no subject still references it (the subjects
     * composite FK is ON DELETE RESTRICT). Idempotent no-op (0 rows) while the
     * grade level is still referenced; never throws.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM grade_levels WHERE id = :id "
            + "AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.grade_level_id = :id)",
            nativeQuery = true)
    int deleteIfNoSubjects(@Param("id") Long id);
}
