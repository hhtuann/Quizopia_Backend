package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.AcademicStatus;
import com.hhtuann.backend.academic.domain.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Subject}.
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    Optional<Subject> findByIdAndSchoolId(Long id, Long schoolId);

    Optional<Subject> findBySchoolIdAndGradeLevelIdAndCodeIgnoreCase(Long schoolId, Long gradeLevelId, String code);

    /**
     * All subjects in a school with a given status, ordered for stable dropdown
     * rendering (grade level, then name). Used by the school-scoped subject list.
     */
    List<Subject> findBySchoolIdAndStatusOrderByGradeLevelIdAscNameAsc(Long schoolId, AcademicStatus status);
}
