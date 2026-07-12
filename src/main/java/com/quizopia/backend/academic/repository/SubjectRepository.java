package com.quizopia.backend.academic.repository;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * All subjects with a given status across ALL schools (admin cross-school
     * oversight), ordered by school then grade level then name. Used when an
     * ACADEMIC_ADMIN lists subjects without narrowing to a single school.
     */
    List<Subject> findByStatusOrderBySchoolIdAscGradeLevelIdAscNameAsc(AcademicStatus status);

    /**
     * Deletes a subject only when no question bank and no exam still reference it
     * (both FKs are ON DELETE RESTRICT). Used by the demo seeder to retire its
     * legacy single demo subject idempotently: a no-op (0 rows) while the subject
     * is still in use, so it never throws or poisons the seed transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM subjects WHERE id = :id "
            + "AND NOT EXISTS (SELECT 1 FROM question_banks qb WHERE qb.subject_id = :id) "
            + "AND NOT EXISTS (SELECT 1 FROM exams e WHERE e.subject_id = :id)",
            nativeQuery = true)
    int deleteIfUnreferenced(@Param("id") Long id);
}
