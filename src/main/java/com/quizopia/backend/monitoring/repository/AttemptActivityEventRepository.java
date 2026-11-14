package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.AttemptActivityEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptActivityEventRepository
        extends JpaRepository<AttemptActivityEvent, Long>, JpaSpecificationExecutor<AttemptActivityEvent> {

    Optional<AttemptActivityEvent> findByEventId(UUID eventId);

    long countByAttemptId(Long attemptId);

    /**
     * Idempotent append for CLIENT-observed events (P4-A2 ingest): inserts
     * unless the eventId already exists anywhere. Returns 1 when stored, 0
     * when the idempotency key was seen before (duplicate).
     */
    @Modifying
    @Query(value = "INSERT INTO attempt_activity_events "
            + "(event_id, attempt_id, event_type, source, client_occurred_at, client_sequence, "
            + "client_instance_id, metadata, server_received_at) "
            + "VALUES (:eventId, :attemptId, :eventType, 'CLIENT', :clientOccurredAt, :clientSequence, "
            + ":clientInstanceId, CAST(:metadata AS jsonb), CURRENT_TIMESTAMP) "
            + "ON CONFLICT (event_id) DO NOTHING",
            nativeQuery = true)
    int insertClientEventIfAbsent(
            @Param("eventId") UUID eventId,
            @Param("attemptId") long attemptId,
            @Param("eventType") String eventType,
            @Param("clientOccurredAt") java.sql.Timestamp clientOccurredAt,
            @Param("clientSequence") long clientSequence,
            @Param("clientInstanceId") UUID clientInstanceId,
            @Param("metadata") String metadata);

    /**
     * Server-confirmed append (lifecycle, ANSWER_ACCEPTED). Called from inside
     * the owning transaction (autosave / start / submit / timeout), so the
     * event commits or rolls back with the fact it records. Plain insert: a
     * server-supplied random eventId colliding is a retriable failure, not an
     * idempotency case.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO attempt_activity_events "
            + "(event_id, attempt_id, event_type, source, metadata, server_received_at) "
            + "VALUES (:eventId, :attemptId, :eventType, 'SERVER', CAST(:metadata AS jsonb), "
            + "COALESCE(:occurredAt, CURRENT_TIMESTAMP))",
            nativeQuery = true)
    int insertServerEvent(
            @Param("eventId") UUID eventId,
            @Param("attemptId") long attemptId,
            @Param("eventType") String eventType,
            @Param("occurredAt") java.sql.Timestamp occurredAt,
            @Param("metadata") String metadata);

    /**
     * Timeline paging uses {@link JpaSpecificationExecutor#findAll} with a
      * typed Specification built by {@code AttemptTimelineService} — NOT a
     * JPQL {@code ":param IS NULL"} shape: Hibernate 7 + PostgreSQL cannot
     * type an unbound null Instant parameter ("could not determine data type
     * of parameter"), which broke every unset-filter timeline read (found by
     * BackendTestsA2A4, 2026-11-14). The Specification composes the
     * (server_received_at, id) keyset cursor + filters dynamically with
     * typed predicates only.
     */

    /** Events of an attempt in (received, id) order — reconnect backfill bulk read. */
    @Query("SELECT e FROM AttemptActivityEvent e WHERE e.attemptId = :attemptId "
            + "ORDER BY e.serverReceivedAt ASC, e.id ASC")
    List<AttemptActivityEvent> findAllByAttemptIdOrdered(@Param("attemptId") long attemptId);

    /** Exists-check used by retention/verification tooling. */
    boolean existsByAttemptIdAndEventType(Long attemptId, ActivityEventType eventType);
}
