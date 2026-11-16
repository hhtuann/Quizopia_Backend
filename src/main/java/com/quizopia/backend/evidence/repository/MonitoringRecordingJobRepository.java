package com.quizopia.backend.evidence.repository;

import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Native-statement repository over {@code monitoring_recording_jobs}
 * (P4-D). Mutations mirror the egress webhook state machine; reads serve
 * clip coverage and the teacher recordings view.
 */
public interface MonitoringRecordingJobRepository extends JpaRepository<MonitoringRecordingJob, UUID> {

    Optional<MonitoringRecordingJob> findByEgressId(String egressId);

    /** Active (non-terminal) runs of one attempt. */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.attemptId = :attemptId "
            + "AND j.status IN ('STARTING','ACTIVE','ENDING')")
    List<MonitoringRecordingJob> findActiveByAttemptId(@Param("attemptId") long attemptId);

    /** Active runs bound to one media binding (camera disconnect stop). */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.bindingId = :bindingId "
            + "AND j.status IN ('STARTING','ACTIVE','ENDING')")
    List<MonitoringRecordingJob> findActiveByBindingId(@Param("bindingId") UUID bindingId);

    /**
     * Runs of an attempt with materialized objects that may overlap
     * {@code [from, to]} — the clip coverage source. Terminal, object-owning
     * rows only: a run without an object never covers anything.
     */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.attemptId = :attemptId "
            + "AND j.kind = :kind AND j.status = 'COMPLETE' AND j.objectKey IS NOT NULL "
            + "AND j.objectDeleted = false AND j.startedAt IS NOT NULL AND j.endedAt IS NOT NULL "
            + "AND j.startedAt <= :to AND j.endedAt >= :from "
            + "ORDER BY j.startedAt ASC")
    List<MonitoringRecordingJob> findCovering(@Param("attemptId") long attemptId,
            @Param("kind") String kind, @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    /** Rolling runs of an attempt, newest pulse first (keep-N retention). */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.attemptId = :attemptId "
            + "AND j.kind = 'ROLLING' AND j.status = 'COMPLETE' AND j.objectKey IS NOT NULL "
            + "AND j.objectDeleted = false ORDER BY j.startedAt DESC")
    List<MonitoringRecordingJob> findRollingNewestFirst(@Param("attemptId") long attemptId);

    /** Object keys referenced by ANY job row of the attempt (orphan pass). */
    @Query("SELECT j.objectKey FROM MonitoringRecordingJob j "
            + "WHERE j.attemptId = :attemptId AND j.objectKey IS NOT NULL")
    List<String> findObjectKeysOfAttempt(@Param("attemptId") long attemptId);

    /** Attempts that own ANY recording rows — orphan-reconcile work list. */
    @Query("SELECT DISTINCT j.attemptId FROM MonitoringRecordingJob j")
    List<Long> findAttemptsWithJobs();

    /** Attempts that own rolling rows — the legacy orphan-reconcile name. */
    @Query("SELECT DISTINCT j.attemptId FROM MonitoringRecordingJob j WHERE j.kind = 'ROLLING'")
    List<Long> findAttemptsWithRollingRows();

    /** Mirrors an egress status webhook onto a job row (authoritative). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_recording_jobs SET status = :status, "
            + "started_at = COALESCE(:startedAt, started_at), "
            + "ended_at = COALESCE(:endedAt, ended_at), "
            + "object_key = COALESCE(:objectKey, object_key), "
            + "bytes = COALESCE(:bytes, bytes), duration_ms = COALESCE(:durationMs, duration_ms), "
            + "error = :error, updated_at = :now WHERE egress_id = :egressId", nativeQuery = true)
    int updateFromEgress(@Param("egressId") String egressId, @Param("status") String status,
            @Param("startedAt") java.sql.Timestamp startedAt,
            @Param("endedAt") java.sql.Timestamp endedAt,
            @Param("objectKey") String objectKey, @Param("bytes") Long bytes,
            @Param("durationMs") Long durationMs, @Param("error") String error,
            @Param("now") java.sql.Timestamp now);

    /**
     * Stop-side ENDING transition — CONDITIONAL on non-terminal status so a
     * concurrently processed egress_ended webhook can never be overwritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_recording_jobs SET status = 'ENDING', "
            + "error = :error, updated_at = :now WHERE egress_id = :egressId "
            + "AND status IN ('STARTING','ACTIVE')", nativeQuery = true)
    int markEnding(@Param("egressId") String egressId, @Param("error") String error,
            @Param("now") java.sql.Timestamp now);

    /** Recording rows past media expiry whose object still needs deletion. */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.objectDeleted = false "
            + "AND j.objectKey IS NOT NULL AND j.expiresAt IS NOT NULL "
            + "AND j.expiresAt < :now ORDER BY j.expiresAt ASC")
    List<MonitoringRecordingJob> findExpired(@Param("now") Instant now);

    /** Conditional object_deleted flag for ONE row (false-deletion guard). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_recording_jobs SET object_deleted = true, "
            + "updated_at = :now WHERE id = :id AND object_deleted = false", nativeQuery = true)
    int markObjectDeleted(@Param("id") UUID id, @Param("now") java.sql.Timestamp now);

    /** Segments of a session's attempts (teacher recordings view). */
    @Query("SELECT j FROM MonitoringRecordingJob j WHERE j.examSessionId = :sessionId "
            + "ORDER BY j.attemptId ASC, j.startedAt ASC")
    List<MonitoringRecordingJob> findBySessionOrdered(@Param("sessionId") long sessionId);
}
