package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link Subject}.
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    Optional<Subject> findByIdAndSchoolId(Long id, Long schoolId);

    Optional<Subject> findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(Long schoolId, Long gradeLevelId, String code);
}
