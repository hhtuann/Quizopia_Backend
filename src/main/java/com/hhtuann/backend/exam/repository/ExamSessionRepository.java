package com.hhtuann.backend.exam.repository;

import com.hhtuann.backend.exam.domain.model.ExamSession;
import com.hhtuann.backend.exam.domain.model.ExamSessionStatus;
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

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    Optional<ExamSession> findByIdAndOwnerTeacherId(Long id, Long ownerTeacherId);

    boolean existsByOwnerTeacherIdAndCodeIgnoreCase(Long ownerTeacherId, String code);

    List<ExamSession> findAllByOwnerTeacherId(Long ownerTeacherId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ExamSession s WHERE s.id = :id")
    Optional<ExamSession> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT s FROM ExamSession s WHERE s.ownerTeacherId = :ownerId "
            + "AND (COALESCE(:search, '') = '' "
            + "  OR LOWER(s.code) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "  OR LOWER(s.title) LIKE LOWER(CONCAT('%', :search, '%'))) "
            + "AND (:status IS NULL OR s.status = :status) "
            + "AND (:examId IS NULL OR s.examVersionId IN "
            + "  (SELECT ev.id FROM ExamVersion ev WHERE ev.examId = :examId))")
    Page<ExamSession> findOwnedByTeacher(@Param("ownerId") Long ownerId,
                                          @Param("search") String search,
                                          @Param("status") ExamSessionStatus status,
                                          @Param("examId") Long examId,
                                          Pageable pageable);

    /**
     * Bulk lazy-close: transitions ALL expired OPEN sessions for the owner to CLOSED.
     * Must be called BEFORE {@link #findOwnedByTeacher} so the status filter and pagination
     * see the effective status. Bulk UPDATE bypasses {@code @Version} (no optimistic-lock race;
     * no managed entities). {@code clearAutomatically=true} clears the persistence context so the
     * subsequent query loads fresh post-close state.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ExamSession s SET s.status = :closed, s.closedAt = :now "
            + "WHERE s.ownerTeacherId = :ownerId AND s.status = :open AND s.endsAt < :now")
    int bulkLazyCloseExpiredOpenSessions(@Param("ownerId") Long ownerId,
                                         @Param("open") ExamSessionStatus openStatus,
                                         @Param("closed") ExamSessionStatus closedStatus,
                                         @Param("now") Instant now);
}
