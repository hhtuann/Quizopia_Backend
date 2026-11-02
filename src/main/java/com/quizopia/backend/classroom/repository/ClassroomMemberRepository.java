package com.quizopia.backend.classroom.repository;

import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ClassroomMember}. Membership uniqueness is enforced by
 * DB constraint {@code uk_classroom_members_classroom_user} (user-based, V12);
 * the legacy {@code uk_classroom_members_classroom_student} remains for
 * school-era rows.
 */
public interface ClassroomMemberRepository extends JpaRepository<ClassroomMember, Long> {

    Page<ClassroomMember> findByClassroomIdOrderByIdAsc(Long classroomId, Pageable pageable);

    /** Full roster (detail view — small N per classroom). */
    List<ClassroomMember> findAllByClassroomIdOrderByIdAsc(Long classroomId);

    /** Batch existing-membership check by user (user-based membership, V12). */
    List<ClassroomMember> findAllByClassroomIdAndUserIdIn(Long classroomId, List<Long> userIds);

    Optional<ClassroomMember> findByClassroomIdAndUserId(Long classroomId, Long userId);

    boolean existsByClassroomIdAndUserId(Long classroomId, Long userId);

    long deleteByClassroomIdAndUserId(Long classroomId, Long userId);

    /** Legacy lookup kept for school-era rows and API compatibility. */
    Optional<ClassroomMember> findByClassroomIdAndStudentProfileId(Long classroomId, Long studentProfileId);

    long deleteByClassroomIdAndStudentProfileId(Long classroomId, Long studentProfileId);

    long countByClassroomId(Long classroomId);
}
