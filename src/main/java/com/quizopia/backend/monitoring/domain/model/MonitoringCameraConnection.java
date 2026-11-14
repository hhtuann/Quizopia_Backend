package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapping the V37 {@code monitoring_camera_connections} table —
 * server-confirmed camera presence periods per media binding (P4-B4, issue
 * #87).
 *
 * <p>One OPEN row ({@code disconnected_at IS NULL}) per binding is enforced
 * by the partial unique index {@code uk_camera_connections_open}; it is the
 * idempotency anchor for duplicate/replayed {@code track_published}
 * webhooks. Mutations go through native statements on
 * {@code MonitoringCameraConnectionRepository} (update-or-insert semantics,
 * {@code ON CONFLICT DO NOTHING}); the entity is the read model.
 *
 * <p>Gaps in monitoring = absence of an open row between two periods; the
 * rows themselves are the evidence (ADR-08: camera loss never mutates
 * answers/deadline/grade).
 */
@Entity
@Table(name = "monitoring_camera_connections")
public class MonitoringCameraConnection {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "binding_id", nullable = false)
    private UUID bindingId;

    @Column(name = "track_sid")
    private String trackSid;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "close_reason", length = 32)
    private String closeReason;

    protected MonitoringCameraConnection() {
        // JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getBindingId() {
        return bindingId;
    }

    public String getTrackSid() {
        return trackSid;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getDisconnectedAt() {
        return disconnectedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public String getCloseReason() {
        return closeReason;
    }
}
