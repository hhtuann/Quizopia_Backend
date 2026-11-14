package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.AiAlertType;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MonitoringAiAlert} (V38
 * {@code monitoring_ai_alerts}) — the aggregation windows of issue #90.
 *
 * <p>All writes are race-safe against the partial unique index
 * {@code uk_ai_alerts_open} (one OPEN window per attempt+alertType): the
 * insert is {@code ON CONFLICT ... DO NOTHING} and the close/extend are
 * guarded {@code WHERE status='OPEN'} statements, so a concurrent batch
 * can never produce two OPEN windows — the loser of an insert race
 * re-reads the winner's window and extends it instead.
 */
public interface MonitoringAiAlertRepository extends JpaRepository<MonitoringAiAlert, Long> {

    /** The single OPEN window of (attempt, alertType) — the aggregation anchor. */
    Optional<MonitoringAiAlert> findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
            Long attemptId, AiAlertType alertType, String status);

    /** Alert windows of a session in id order (oldest first). */
    List<MonitoringAiAlert> findByExamSessionIdOrderByIdAsc(Long examSessionId);

    /**
     * Opens a new alert window (count=1). Returns 1 when stored, 0 when a
     * concurrent transaction already opened the OPEN window (caller falls
     * back to extending it).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO monitoring_ai_alerts "
            + "(attempt_id, exam_session_id, user_id, alert_type, status, first_detected_at, "
            + "last_detected_at, detection_count, max_confidence, latest_payload, model_id, "
            + "model_version, provenance, created_at, updated_at) "
            + "VALUES (:attemptId, :sessionId, :userId, :alertType, 'OPEN', :detectedAt, "
            + ":detectedAt, 1, CAST(:maxConfidence AS double precision), CAST(:payload AS jsonb), "
            + ":modelId, :modelVersion, :provenance, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (attempt_id, alert_type) WHERE status = 'OPEN' DO NOTHING",
            nativeQuery = true)
    int insertOpenIfAbsent(
            @Param("attemptId") long attemptId,
            @Param("sessionId") long sessionId,
            @Param("userId") long userId,
            @Param("alertType") String alertType,
            @Param("detectedAt") Timestamp detectedAt,
            @Param("maxConfidence") Double maxConfidence,
            @Param("payload") String payload,
            @Param("modelId") String modelId,
            @Param("modelVersion") String modelVersion,
            @Param("provenance") String provenance);

    /**
     * Extends the OPEN window: count+1 (atomic in SQL — concurrent batches
     * never lose an increment), last_detected_at=observedAt, max_confidence
     * = max of (stored, incoming), latest payload wins. Returns the new
     * detection_count so callers can decide the AI_ALERT_UPDATED
     * notification without a re-read race.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_ai_alerts SET "
            + "last_detected_at = :observedAt, "
            + "detection_count = detection_count + 1, "
            + "max_confidence = (SELECT MAX(v) FROM (VALUES (max_confidence), "
            + "(CAST(:maxConfidence AS double precision))) AS t(v)), "
            + "latest_payload = CAST(:payload AS jsonb), model_id = :modelId, "
            + "model_version = :modelVersion, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :alertId AND status = 'OPEN'",
            nativeQuery = true)
    int extendOpenAlert(
            @Param("alertId") long alertId,
            @Param("observedAt") Timestamp observedAt,
            @Param("maxConfidence") Double maxConfidence,
            @Param("payload") String payload,
            @Param("modelId") String modelId,
            @Param("modelVersion") String modelVersion);

    /** Detection count of the window after {@link #extendOpenAlert} ran. */
    @Query(value = "SELECT detection_count FROM monitoring_ai_alerts WHERE id = :alertId",
            nativeQuery = true)
    Integer detectionCountOf(@Param("alertId") long alertId);

    /**
     * Closes a window (gap &gt; cooldown: the incident ends, a new window
     * will open for the next detection). Guarded on status so a concurrent
     * close is an exact no-op.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_ai_alerts SET status = 'CLOSED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = :alertId AND status = 'OPEN'",
            nativeQuery = true)
    int closeOpenAlert(@Param("alertId") long alertId);
}
