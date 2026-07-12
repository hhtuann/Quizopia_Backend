package com.quizopia.backend.exam.repository;

import com.quizopia.backend.exam.domain.model.ExamSessionParticipant;
import com.quizopia.backend.exam.domain.model.ExamSessionParticipantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamSessionParticipantRepository extends JpaRepository<ExamSessionParticipant, Long> {

    List<ExamSessionParticipant> findAllByExamSessionIdOrderByIdAsc(Long examSessionId);

    boolean existsByExamSessionIdAndStudentProfileId(Long examSessionId, Long studentProfileId);

    Optional<ExamSessionParticipant> findByIdAndExamSessionId(Long id, Long examSessionId);

    /**
     * Pessimistic-write lock a participant scoped to a session (F4). Used by
     * block/unblock AFTER the session has been locked, so concurrent toggles
     * serialize on the participant row and re-read the latest committed state
     * — no {@code @Version} OptimisticLockException on flush.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ExamSessionParticipant p WHERE p.id = :id AND p.examSessionId = :sessionId")
    Optional<ExamSessionParticipant> findByIdAndExamSessionIdForUpdate(@Param("id") Long id,
                                                                       @Param("sessionId") Long sessionId);

    List<ExamSessionParticipant> findAllByExamSessionIdAndStudentProfileIdIn(Long examSessionId, List<Long> studentProfileIds);

    long countByExamSessionId(Long examSessionId);

    @Query("SELECT p.examSessionId, COUNT(p) FROM ExamSessionParticipant p "
            + "WHERE p.examSessionId IN :sessionIds GROUP BY p.examSessionId")
    List<Object[]> countByExamSessionIds(@Param("sessionIds") List<Long> sessionIds);

    @Query("SELECT p FROM ExamSessionParticipant p WHERE p.examSessionId = :sessionId "
            + "AND (:status IS NULL OR p.status = :status)")
    Page<ExamSessionParticipant> findParticipants(@Param("sessionId") Long sessionId,
                                                   @Param("status") ExamSessionParticipantStatus status,
                                                   Pageable pageable);
}
