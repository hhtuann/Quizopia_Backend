package com.hhtuann.backend.academic.repository;

import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link TeacherProfile}. A user has at most one teacher
 * profile (unique {@code user_id}).
 */
public interface TeacherProfileRepository extends JpaRepository<TeacherProfile, Long> {

    Optional<TeacherProfile> findByUserId(Long userId);

    Optional<TeacherProfile> findByUserIdAndSchoolId(Long userId, Long schoolId);

    boolean existsByUserId(Long userId);
}
