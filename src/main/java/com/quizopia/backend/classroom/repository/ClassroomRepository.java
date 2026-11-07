package com.quizopia.backend.classroom.repository;

import com.quizopia.backend.classroom.domain.model.Classroom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link Classroom}. Classrooms are owned by a USER; code
 * uniqueness is owner-scoped and case-insensitive (DB index
 * {@code uk_classrooms_owner_user_code_ci}); the exists-check is the
 * friendly pre-flight, the unique index is the race fallback.
 */
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

    /** Classrooms owned by a USER, newest first (GET /api/classrooms/my). */
    Page<Classroom> findByOwnerUserIdOrderByCreatedAtDescIdDesc(Long ownerUserId, Pageable pageable);

    /** Owner-scoped case-insensitive code conflict pre-check. */
    boolean existsByOwnerUserIdAndCodeIgnoreCase(Long ownerUserId, String code);

    /** Owner-scoped case-insensitive lookup (find-or-create / detail resolution). */
    Optional<Classroom> findByOwnerUserIdAndCodeIgnoreCase(Long ownerUserId, String code);
}
