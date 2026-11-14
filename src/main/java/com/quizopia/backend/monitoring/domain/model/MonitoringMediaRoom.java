package com.quizopia.backend.monitoring.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping the V37 {@code monitoring_media_rooms} table — the ONE
 * opaque LiveKit room per monitored session (P4-B1, issue #84).
 *
 * <p><b>Read model:</b> rows are created exclusively by the lazy upsert in
 * {@code MonitoringMediaRoomRepository.insertIgnore} (INSERT … ON CONFLICT
 * (exam_session_id) DO NOTHING, then read back); the entity itself is never
 * persisted or mutated through the persistence context.
 *
 * <p><b>Room name invariant:</b> {@code roomName} is an unguessable slug
 * ({@code "s-" + UUID-without-dashes}, lowercase, ≤ 64 chars — DB CHECK
 * {@code ^[a-z0-9-]{8,64}$}) never derived from user or session data, so a
 * guessed room name gains nothing.
 */
@Entity
@Table(name = "monitoring_media_rooms")
public class MonitoringMediaRoom {

    /** Primary key IS the session id — strictly one room per session. */
    @Id
    @Column(name = "exam_session_id")
    private Long examSessionId;

    @Column(name = "room_name", nullable = false, unique = true)
    private String roomName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MonitoringMediaRoom() {
        // JPA
    }

    public Long getExamSessionId() {
        return examSessionId;
    }

    public String getRoomName() {
        return roomName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
