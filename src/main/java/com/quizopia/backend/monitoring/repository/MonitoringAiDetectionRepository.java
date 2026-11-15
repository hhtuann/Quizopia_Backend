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
     *
     * <p>V39 also enforces a partial UNIQUE (attempt_id, provenance,
     * sequence) on non-quarantined rows: a repeated sequence under a FRESH
     * eventId raises a unique violation which the caller maps to the same
     * DUPLICATE outcome (replay with invented ids cannot inflate counts).
     */
    /**
     * P2-1 replay pre-check: has this (attempt, provenance, sequence)
     * already been stored (non-quarantined)? Mirrors the V39 partial
     * unique {@code ux_ai_detections_attempt_prov_seq} so the common
     * replay case never reaches the INSERT (a poisoned statement would
     * abort the caller's transaction). The index remains the hard
     * guarantee for concurrent races.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM monitoring_ai_detections "
            + "WHERE attempt_id = :attemptId AND provenance = :provenance "
            + "AND client_sequence = :clientSequence AND NOT quarantined)",
            nativeQuery = true)
    boolean sequenceSeen(@Param("attemptId") long attemptId,
            @Param("provenance") String provenance,
            @Param("clientSequence") long clientSequence);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO monitoring_ai_detections "
            + "(attempt_id, exam_session_id, user_id, media_binding_id, event_id, provenance, "
            + "event_type, model_id, model_version, runtime, observed_at, quarantined, "
            + "quarantine_reason, frame_width, frame_height, processing_ms, payload, late_ms, "
            + "client_sequence, server_received_at) "
            + "VALUES (:attemptId, :sessionId, :userId, CAST(:mediaBindingId AS uuid), :eventId, "
            + ":provenance, :eventType, :modelId, :modelVersion, :runtime, :observedAt, "
            + ":quarantined, CAST(:quarantineReason AS varchar), :frameWidth, :frameHeight, "
            + ":processingMs, CAST(:payload AS jsonb), CAST(:lateMs AS bigint), "
            + "CAST(:clientSequence AS bigint), CURRENT_TIMESTAMP) "
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
            @Param("lateMs") Long lateMs,
            @Param("clientSequence") Long clientSequence);
}
