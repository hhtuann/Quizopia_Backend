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

    /**
     * Housekeeping: TERMINAL entries (REVOKED/DECLINED) older than the cutoff.
     * Review R2-SEC P1: LINKED is live state and must never be purged here.
     * Note: deleting a DECLINED row ends that classroom's opt-out history —
     * the documented policy is that the opt-out window equals the retention
     * window (docs/e2e-local.md).
     */
    @Modifying
    @Query("""
            delete from ClassroomRosterEntry e
            where e.status in (
                  com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.REVOKED,
                  com.quizopia.backend.classroom.domain.model.ClassroomRosterEntry$RosterStatus.DECLINED)
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

    /**
     * Scalar email read (NO entity load) — lets mutating flows locate the row
     * without pre-loading a managed instance that could go stale before the
     * locking read (review R2-SEC/TX P1: lock on FIRST entity load).
     */
    @Query("select e.emailNormalized from ClassroomRosterEntry e "
            + "where e.id = :id and e.classroom.id = :classroomId")
    Optional<String> findEmailByIdAndClassroomId(
            @Param("id") Long id, @Param("classroomId") Long classroomId);

    /** Scalar classroom-id read (no entity load) for lock-on-first-load flows. */
    @Query("select e.classroom.id from ClassroomRosterEntry e where e.id = :id")
    Optional<Long> findClassroomIdById(@Param("id") Long id);

    /** Review M3-01: lock an entry by id regardless of status (revoke/accept). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ClassroomRosterEntry e where e.id = :id and e.classroom.id = :classroomId")
    Optional<ClassroomRosterEntry> findByIdAndClassroomIdForUpdate(
            @Param("id") Long id, @Param("classroomId") Long classroomId);
}
