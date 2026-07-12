package com.quizopia.backend.academic.repository;

import com.quizopia.backend.academic.domain.model.StudentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link StudentProfile}. A user has at most one student
 * profile (unique {@code user_id}).
 */
public interface StudentProfileRepository extends JpaRepository<StudentProfile, Long> {

    Optional<StudentProfile> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
