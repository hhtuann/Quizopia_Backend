package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link GradeLevel}.
 */
public interface GradeLevelRepository extends JpaRepository<GradeLevel, Long> {

    Optional<GradeLevel> findBySchoolIdAndCodeIgnoreCase(Long schoolId, String code);
}
