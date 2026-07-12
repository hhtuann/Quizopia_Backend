package com.quizopia.backend.classroom.repository;

import com.quizopia.backend.classroom.domain.model.ClassroomMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ClassroomMember}. Membership uniqueness is enforced by
 * DB constraint {@code uk_classroom_members_classroom_student}.
 */
public interface ClassroomMemberRepository extends JpaRepository<ClassroomMember, Long> {

    Page<ClassroomMember> findByClassroomIdOrderByIdAsc(Long classroomId, Pageable pageable);

    /** Full roster (detail view — small N per classroom). */
    List<ClassroomMember> findAllByClassroomIdOrderByIdAsc(Long classroomId);

    /** Batch existing-membership check for bulk add (partial-success classification). */
    List<ClassroomMember> findAllByClassroomIdAndStudentProfileIdIn(Long classroomId, List<Long> studentProfileIds);

    Optional<ClassroomMember> findByClassroomIdAndStudentProfileId(Long classroomId, Long studentProfileId);

    boolean existsByClassroomIdAndStudentProfileId(Long classroomId, Long studentProfileId);

    long deleteByClassroomIdAndStudentProfileId(Long classroomId, Long studentProfileId);

    long countByClassroomId(Long classroomId);
}
