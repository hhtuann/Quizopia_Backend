package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * JPA entity mapping the V34 {@code attempt_activity_events} table — the
 * durable append-only unified timeline read model (P4-A2/#81, P4-A4/#83).
 *
 * <p><b>Append-only:</b> rows are never updated or deleted (except by the
 * future P4-D retention sweep). Corrections are new events.
 *
 * <p><b>Identity/idempotency:</b> {@code eventId} is the client- (or
 * server-) supplied UUID with a UNIQUE constraint; the ingest path uses
 * {@code ON CONFLICT (event_id) DO NOTHING} so retried batches are exact
 * no-ops (duplicates reported, never double-stored).
 *
 * <p><b>Provenance:</b> {@code source} distinguishes client observations
 * from server-confirmed records; the timeline API exposes it verbatim so the
 * UI can label trust levels. Client timestamps are stored VERBATIM — clock
 * skew and out-of-order delivery are retained as observations; the stable
 * chronological cursor is always (server_received_at, id).
 *
 * <p><b>No answer content:</b> ANSWER_ACCEPTED rows carry only
 * {attemptQuestionId, acceptedSequence} in metadata; ingest rejects
 * client-supplied answer payloads. The entity is used for reads; inserts go
 * through the repository native statements.
 */
@Entity
@Table(name = "attempt_activity_events")
public class AttemptActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ActivityEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private ActivityEventSource source;

    @Column(name = "client_occurred_at")
    private Instant clientOccurredAt;

    @Column(name = "client_sequence")
    private Long clientSequence;

    @Column(name = "client_instance_id")
    private UUID clientInstanceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private JsonNode metadata;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    protected AttemptActivityEvent() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public ActivityEventType getEventType() {
        return eventType;
    }

    public ActivityEventSource getSource() {
        return source;
    }

    public Instant getClientOccurredAt() {
        return clientOccurredAt;
    }

    public Long getClientSequence() {
        return clientSequence;
    }

    public UUID getClientInstanceId() {
        return clientInstanceId;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }
}
