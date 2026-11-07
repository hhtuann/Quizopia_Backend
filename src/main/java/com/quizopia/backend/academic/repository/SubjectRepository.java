package com.quizopia.backend.academic.repository;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Subject} — the global subject catalog (school
 * scope removed by the user-centric refactor, V24).
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    Optional<Subject> findByGradeLevelIdAndCodeIgnoreCase(Long gradeLevelId, String code);

    /**
     * All subjects with a given status, ordered for stable dropdown
     * rendering (grade level, then name).
     */
    List<Subject> findByStatusOrderByGradeLevelIdAscNameAsc(AcademicStatus status);
}
