package com.quizopia.backend.classroom.repository;

import com.quizopia.backend.classroom.domain.model.Classroom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository for {@link Classroom}. Code uniqueness is OWNER-scoped and
 * case-insensitive (DB index {@code uk_classrooms_owner_code_ci}); the
 * {@code existsByOwnerTeacherIdAndCodeIgnoreCase} check is the friendly
 * pre-flight, the unique index is the race fallback.
 */
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

    /** List classrooms owned by a teacher, newest first (GET /api/classrooms/my). */
    Page<Classroom> findByOwnerTeacherIdOrderByCreatedAtDescIdDesc(Long ownerTeacherId, Pageable pageable);

    /** Owner-scoped case-insensitive code conflict pre-check. */
    boolean existsByOwnerTeacherIdAndCodeIgnoreCase(Long ownerTeacherId, String code);

    /** Owner-scoped case-insensitive lookup (find-or-create / detail resolution). */
    Optional<Classroom> findByOwnerTeacherIdAndCodeIgnoreCase(Long ownerTeacherId, String code);

    /** Resolve a classroom by (school_id, id) — the composite-FK target form. */
    @Query("SELECT c FROM Classroom c WHERE c.schoolId = :schoolId AND c.id = :id")
    Optional<Classroom> findBySchoolIdAndId(@Param("schoolId") Long schoolId, @Param("id") Long id);
}
