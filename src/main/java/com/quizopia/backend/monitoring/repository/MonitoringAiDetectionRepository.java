package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringAiDetection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Repository for {@link MonitoringAiDetection} (V38
 * {@code monitoring_ai_detections}).
 */
public interface MonitoringAiDetectionRepository extends JpaRepository<MonitoringAiDetection, Long> {

    /**
     * Idempotent append for both ingest paths (P4-C, CONTRACT §3.1):
     * inserts unless the eventId already exists anywhere. Returns 1 when
     * stored, 0 when the idempotency key was seen before (duplicate) — a
     * duplicate NEVER reaches aggregation. Quarantined rows use the same
     * statement ({@code quarantined=true} + reason): they are stored for
     * the record but never alert.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO monitoring_ai_detections "
            + "(attempt_id, exam_session_id, user_id, media_binding_id, event_id, provenance, "
            + "event_type, model_id, model_version, runtime, observed_at, quarantined, "
            + "quarantine_reason, frame_width, frame_height, processing_ms, payload, late_ms, "
            + "server_received_at) "
            + "VALUES (:attemptId, :sessionId, :userId, CAST(:mediaBindingId AS uuid), :eventId, "
            + ":provenance, :eventType, :modelId, :modelVersion, :runtime, :observedAt, "
            + ":quarantined, CAST(:quarantineReason AS varchar), :frameWidth, :frameHeight, "
            + ":processingMs, CAST(:payload AS jsonb), CAST(:lateMs AS bigint), CURRENT_TIMESTAMP) "
            + "ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("attemptId") long attemptId,
            @Param("sessionId") long sessionId,
            @Param("userId") long userId,
            @Param("mediaBindingId") UUID mediaBindingId,
            @Param("eventId") UUID eventId,
            @Param("provenance") String provenance,
            @Param("eventType") String eventType,
            @Param("modelId") String modelId,
            @Param("modelVersion") String modelVersion,
            @Param("runtime") String runtime,
            @Param("observedAt") Timestamp observedAt,
            @Param("quarantined") boolean quarantined,
            @Param("quarantineReason") String quarantineReason,
            @Param("frameWidth") int frameWidth,
            @Param("frameHeight") int frameHeight,
            @Param("processingMs") int processingMs,
            @Param("payload") String payload,
            @Param("lateMs") Long lateMs);
}
