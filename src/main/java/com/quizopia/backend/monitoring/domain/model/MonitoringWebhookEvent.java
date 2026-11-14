package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapping the V37 {@code monitoring_webhook_events} ledger — the
 * dedupe/replay guard for processed LiveKit webhook events (P4-B4, issue
 * #87).
 *
 * <p>{@code dedupeKey} is the verifier's deterministic sha256 hex (64 chars)
 * over the event's identity-defining fields; it is the primary key, so the
 * insert path is {@code INSERT ... ON CONFLICT (dedupe_key) DO NOTHING} and
 * a zero-row result means the delivery was already processed — routing is
 * skipped entirely. The same transaction inserts the ledger row BEFORE any
 * domain routing, so a crash between the two can never double-apply.
 *
 * <p>Rows are swept after 7 days by {@code CameraHousekeepingScheduler};
 * the entity exists for mapping/validation and reads — writes go through
 * the repository's native insert.
 */
@Entity
@Table(name = "monitoring_webhook_events")
public class MonitoringWebhookEvent {

    /** sha256 hex, exactly 64 chars — column is CHAR(64), not VARCHAR. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dedupe_key", length = 64)
    private String dedupeKey;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected MonitoringWebhookEvent() {
        // JPA
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
