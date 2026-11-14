package com.quizopia.backend.monitoring.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JdbcTemplate projections over the V37 monitoring tables (P4-B4, issue #87).
 *
 * <p>Read-side fallback used by webhook routing and reconciliation until the
 * binding/preflight JPA repositories land (P4-B2); the projection mirrors
 * exactly the {@code findByMediaIdentityAndActiveToIsNull}-style lookups the
 * repositories will expose, so swapping is mechanical. All statements are
 * read-only and safe inside a caller's transaction.
 *
 * <p>Identity semantics (ADR-08): {@code media_identity} is a fresh opaque
 * UUID per preflight, so resolving a webhook by identity can never
 * reattribute it to a different attempt of the same user.
 */
@Component
public class MonitoringCameraQueries {

    /** Active binding projection (V37 {@code monitoring_media_bindings}). */
    public record BindingRow(UUID id, Long attemptId, long sessionId, long userId, String mediaIdentity) {
    }

    /** Open camera-connection projection for one session. */
    public record OpenConnectionRow(UUID id, UUID bindingId, String trackSid) {
    }

    /** Monitored OPEN session with its media room (reconciliation candidate). */
    public record SessionRoom(long sessionId, String roomName) {
    }

    private static final String BINDING_COLUMNS =
            "id, attempt_id, exam_session_id, user_id, media_identity";

    private final JdbcTemplate jdbc;

    public MonitoringCameraQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, i) -> new BindingRow(
            rs.getObject("id", UUID.class),
            rs.getObject("attempt_id") == null ? null : rs.getLong("attempt_id"),
            rs.getLong("exam_session_id"),
            rs.getLong("user_id"),
            rs.getString("media_identity"));

    /** The single active (unterminated) binding owning {@code mediaIdentity}, if any. */
    public Optional<BindingRow> findActiveBindingByMediaIdentity(String mediaIdentity) {
        List<BindingRow> rows = jdbc.query(
                "SELECT " + BINDING_COLUMNS + " FROM monitoring_media_bindings "
                        + "WHERE media_identity = ? AND active_to IS NULL "
                        + "ORDER BY epoch DESC LIMIT 1",
                BINDING_MAPPER, mediaIdentity);
        return rows.stream().findFirst();
    }

    /**
     * Latest binding (any state) owning {@code mediaIdentity} — termination
     * routing for late webhooks after the binding was already closed.
     */
    public Optional<BindingRow> findLatestBindingByMediaIdentity(String mediaIdentity) {
        List<BindingRow> rows = jdbc.query(
                "SELECT " + BINDING_COLUMNS + " FROM monitoring_media_bindings "
                        + "WHERE media_identity = ? "
                        + "ORDER BY epoch DESC LIMIT 1",
                BINDING_MAPPER, mediaIdentity);
        return rows.stream().findFirst();
    }

    /** All active bindings of a session (reconciliation works per session). */
    public List<BindingRow> findActiveBindingsBySession(long sessionId) {
        return jdbc.query(
                "SELECT " + BINDING_COLUMNS + " FROM monitoring_media_bindings "
                        + "WHERE exam_session_id = ? AND active_to IS NULL",
                BINDING_MAPPER, sessionId);
    }

    /** Open camera connections of a session's bindings, keyed by binding id. */
    public Map<UUID, OpenConnectionRow> findOpenConnectionsBySession(long sessionId) {
        return jdbc.query(
                        "SELECT c.id, c.binding_id, c.track_sid "
                                + "FROM monitoring_camera_connections c "
                                + "JOIN monitoring_media_bindings b ON b.id = c.binding_id "
                                + "WHERE b.exam_session_id = ? AND c.disconnected_at IS NULL",
                        (rs, i) -> new OpenConnectionRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("binding_id", UUID.class),
                                rs.getString("track_sid")),
                        sessionId)
                .stream()
                .collect(Collectors.toMap(OpenConnectionRow::bindingId, Function.identity()));
    }

    /** Attempt status, or null when unbound/deleted — only IN_PROGRESS receives camera events. */
    public String attemptStatus(Long attemptId) {
        if (attemptId == null) {
            return null;
        }
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM attempts WHERE id = ?", String.class, attemptId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** Monitored OPEN sessions that have a media room (reconciliation candidates). */
    public List<SessionRoom> findMonitoredOpenSessions() {
        return jdbc.query(
                "SELECT DISTINCT s.id, r.room_name "
                        + "FROM exam_sessions s "
                        + "JOIN session_monitoring_policies p "
                        + "  ON p.exam_session_id = s.id AND p.monitoring_enabled "
                        + "JOIN monitoring_media_rooms r ON r.exam_session_id = s.id "
                        + "WHERE s.status = 'OPEN'",
                (rs, i) -> new SessionRoom(rs.getLong("id"), rs.getString("room_name")));
    }

    /** Purge helper used by housekeeping (kept next to the ledger constants). */
    public int deleteLedgerOlderThan(java.time.Instant cutoff) {
        return jdbc.update("DELETE FROM monitoring_webhook_events WHERE received_at < ?",
                Timestamp.from(cutoff));
    }
}
