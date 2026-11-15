package com.quizopia.backend.evidence.repository;

import com.quizopia.backend.evidence.domain.MonitoringEvidence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code monitoring_evidence} reads/writes (P4-D, issues #91/#93/#94). */
public interface MonitoringEvidenceRepository extends JpaRepository<MonitoringEvidence, UUID> {

    Optional<MonitoringEvidence> findByIdempotencyKey(String idempotencyKey);

    long countByAlertIdAndStatusNot(long alertId, String excludedStatus);

    /** Snapshot budget — kind-scoped so teacher CLIP rows never consume it. */
    long countByAlertIdAndKindAndStatusNot(long alertId, String kind, String excludedStatus);

    long countByAttemptIdAndKind(long attemptId, String kind);

    List<MonitoringEvidence> findByExamSessionIdOrderByCreatedAtDesc(Long examSessionId, Pageable pageable);

    List<MonitoringEvidence> findByExamSessionIdAndAlertIdOrderByCreatedAtDesc(
            Long examSessionId, Long alertId);

    List<MonitoringEvidence> findByExamSessionIdAndKindOrderByCreatedAtDesc(
            Long examSessionId, String kind, Pageable pageable);

    Optional<MonitoringEvidence> findByIdAndExamSessionId(UUID id, Long examSessionId);

    /** Idempotent live-or-done clip of an alert per provenance mode. */
    @Query("SELECT e FROM MonitoringEvidence e WHERE e.alertId = :alertId "
            + "AND e.kind = 'CLIP' AND e.provenance = :provenance "
            + "AND e.status IN ('PENDING','AVAILABLE','PARTIAL')")
    Optional<MonitoringEvidence> findReusableClip(@Param("alertId") long alertId,
            @Param("provenance") String provenance);

    /** Retention candidates: past expiry, not yet EXPIRED. */
    @Query("SELECT e FROM MonitoringEvidence e WHERE e.status <> 'EXPIRED' "
            + "AND e.expiresAt < :now ORDER BY e.expiresAt ASC")
    List<MonitoringEvidence> findExpired(@Param("now") Instant now, Pageable pageable);

    /** Demo purge / session cleanup targets: non-expired rows of a session. */
    @Query("SELECT e FROM MonitoringEvidence e WHERE e.examSessionId = :sessionId "
            + "AND e.status IN ('PENDING','AVAILABLE','PARTIAL')")
    List<MonitoringEvidence> findPurgeable(@Param("sessionId") long sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_evidence SET status = 'EXPIRED', "
            + "object_key = NULL, sha256 = NULL, updated_at = :now "
            + "WHERE id IN (:ids)", nativeQuery = true)
    int markExpired(@Param("ids") List<UUID> ids, @Param("now") java.sql.Timestamp now);

    /** All rows of one attempt (orphan reference lookup; bounded by budgets). */
    List<MonitoringEvidence> findByAttemptId(Long attemptId);

    /** Session ids owning an attempt's evidence (orphan reconciliation lookup). */
    @Query("SELECT DISTINCT e.examSessionId FROM MonitoringEvidence e WHERE e.attemptId = :attemptId")
    List<Long> findSessionIdsOfAttempt(@Param("attemptId") long attemptId);
}