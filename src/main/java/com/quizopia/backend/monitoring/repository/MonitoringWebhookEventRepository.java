package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;

/**
 * Webhook dedupe ledger (V37 {@code monitoring_webhook_events}). The only
 * write is the idempotent acceptance insert: 0 rows means the delivery was
 * already processed (replay/retry) and routing MUST be skipped.
 */
public interface MonitoringWebhookEventRepository
        extends JpaRepository<MonitoringWebhookEvent, String> {

    /**
     * Records a delivery unless its dedupe key was seen before. Returns 1
     * when this call accepted the event (route it), 0 when it is a
     * duplicate (skip). Runs inside the caller's transaction — the ledger
     * row and the domain routing commit or roll back together.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO monitoring_webhook_events (dedupe_key, event_type, received_at) "
            + "VALUES (:dedupeKey, :eventType, :receivedAt) "
            + "ON CONFLICT (dedupe_key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("dedupeKey") String dedupeKey,
                       @Param("eventType") String eventType,
                       @Param("receivedAt") Timestamp receivedAt);
}
