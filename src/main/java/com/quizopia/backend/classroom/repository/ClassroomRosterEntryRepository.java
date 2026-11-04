package com.quizopia.backend.classroom.repository;

import com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry;
import com.quizopia.backend.classroom.domain.model.Classroom;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ClassroomRosterEntry}. PENDING invitations are
 * matched by canonical email; the partial unique index
 * {@code uk_roster_pending_classroom_email} guarantees one PENDING entry
 * per (classroom, email).
 */
public interface ClassroomRosterEntryRepository extends JpaRepository<ClassroomRosterEntry, Long> {

    Optional<ClassroomRosterEntry> findByIdAndClassroomId(Long id, Long classroomId);

    /** All PENDING entries for an email — the reconciliation lookup. */
    List<ClassroomRosterEntry> findAllByEmailNormalizedAndStatus(String emailNormalized,
            ClassroomRosterEntry.RosterStatus status);

    /**
     * Locks the single PENDING entry of a classroom+email for the
     * reconciliation/link path (prevents double-link races).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from ClassroomRosterEntry e
            where e.classroom.id = :classroomId
              and e.emailNormalized = :email
              and e.status = com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.PENDING
            """)
    Optional<ClassroomRosterEntry> findPendingForUpdate(
            @Param("classroomId") Long classroomId,
            @Param("email") String emailNormalized);

    boolean existsByClassroomIdAndEmailNormalizedAndStatus(Long classroomId, String emailNormalized,
            ClassroomRosterEntry.RosterStatus status);

    Page<ClassroomRosterEntry> findByClassroomIdOrderByCreatedAtDesc(Long classroomId, Pageable pageable);

    /** Entries a specific learner is linked to (leave/decline flows). */
    List<ClassroomRosterEntry> findAllByUserIdAndStatus(Long userId, ClassroomRosterEntry.RosterStatus status);

    /** Count of PENDING entries per classroom (detail views). */
    long countByClassroomIdAndStatus(Long classroomId, ClassroomRosterEntry.RosterStatus status);

    /** Housekeeping: terminal entries older than the cutoff. */
    @Modifying
    @Query("""
            delete from ClassroomRosterEntry e
            where e.status <> com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.PENDING
              and e.updatedAt < :cutoff
            """)
    int deleteTerminalOlderThan(@Param("cutoff") Instant cutoff);

    /** Classrooms that have any roster activity for a user's email. */
    @Query("""
            select e.classroom from ClassroomRosterEntry e
            where e.emailNormalized = :email
              and e.status = com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.PENDING
            """)
    List<Classroom> findPendingClassroomsForEmail(@Param("email") String emailNormalized);

    /**
     * Identity-M4: ALL active (PENDING/LINKED) emails of a classroom —
     * unpaged (review M4-QA: a paged read silently misses older invitations,
     * making preview disagree with confirm).
     */
    @Query("""
            select e.emailNormalized from ClassroomRosterEntry e
            where e.classroom.id = :classroomId
              and e.status in (
                  com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.PENDING,
                  com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.LINKED)
            """)
    java.util.List<String> findActiveEmailsByClassroomId(@Param("classroomId") Long classroomId);
}
