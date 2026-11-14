package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringCameraConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Camera-connection periods (V37 {@code monitoring_camera_connections}).
 *
 * <p>All writes are native statements with the "one OPEN row per binding"
 * partial unique index as the idempotency anchor: duplicate/replayed
 * {@code track_published} webhooks and concurrent reconciliation sweeps
 * converge on a single open row instead of duplicating periods or events.
 * Callers must run inside a transaction ({@code @Modifying} native writes).
 */
public interface MonitoringCameraConnectionRepository
        extends JpaRepository<MonitoringCameraConnection, UUID> {

    /** The OPEN row of a binding, if any. */
    Optional<MonitoringCameraConnection> findByBindingIdAndDisconnectedAtIsNull(UUID bindingId);

    /** Most recently closed period of a binding — reconnect-gap detection. */
    Optional<MonitoringCameraConnection> findFirstByBindingIdAndDisconnectedAtIsNotNullOrderByDisconnectedAtDesc(
            UUID bindingId);

    /**
     * Refreshes the open row of {@code bindingId} (track sid + verification
     * timestamp). Returns 1 when an open row existed, 0 otherwise.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_camera_connections "
            + "SET track_sid = CAST(:trackSid AS varchar), last_verified_at = :at "
            + "WHERE binding_id = :bindingId AND disconnected_at IS NULL",
            nativeQuery = true)
    int claimOpenRow(@Param("bindingId") UUID bindingId,
                     @Param("trackSid") String trackSid,
                     @Param("at") Timestamp at);

    /**
     * Opens a new connection period unless one is already open (partial
     * unique index {@code uk_camera_connections_open}). Returns 1 when this
     * call opened the row, 0 when an open row already existed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO monitoring_camera_connections "
            + "(id, binding_id, track_sid, connected_at, last_verified_at) "
            + "VALUES (:id, :bindingId, CAST(:trackSid AS varchar), :at, :at) "
            + "ON CONFLICT (binding_id) WHERE disconnected_at IS NULL DO NOTHING",
            nativeQuery = true)
    int insertOpenIfAbsent(@Param("id") UUID id,
                           @Param("bindingId") UUID bindingId,
                           @Param("trackSid") String trackSid,
                           @Param("at") Timestamp at);

    /** Touches the verification timestamp of one open row (reconciliation match). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_camera_connections "
            + "SET last_verified_at = :at "
            + "WHERE binding_id = :bindingId AND disconnected_at IS NULL",
            nativeQuery = true)
    int touchOpenRow(@Param("bindingId") UUID bindingId, @Param("at") Timestamp at);

    /**
     * Closes the open row of {@code bindingId} idempotently. Returns 1 when
     * this call closed a row, 0 when none was open (already closed / late
     * webhook) — callers emit {@code CAMERA_DISCONNECTED} only on 1.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE monitoring_camera_connections "
            + "SET disconnected_at = :at, close_reason = :closeReason "
            + "WHERE binding_id = :bindingId AND disconnected_at IS NULL",
            nativeQuery = true)
    int closeOpenRow(@Param("bindingId") UUID bindingId,
                     @Param("closeReason") String closeReason,
                     @Param("at") Timestamp at);
}
