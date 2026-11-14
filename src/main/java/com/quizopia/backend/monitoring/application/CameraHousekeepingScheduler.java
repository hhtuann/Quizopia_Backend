package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.roomservice.MediaRoomGateway;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;
import com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hourly hygiene over the P4-B camera bookkeeping tables (issue #87):
 *
 * <ol>
 *   <li>preflights ACTIVE/VERIFIED past {@code expires_at + 1h} grace →
 *       EXPIRED (their readiness leases die with them);</li>
 *   <li>bound bindings of non-OPEN sessions or finished attempts →
 *       {@code active_to = now} (close without the revoke flag — this is
 *       hygiene, not a teacher action) plus their OPEN camera connection
 *       periods closed the same transaction ({@code close_reason} 'CLOSED',
 *       so camera-status can never show a terminated binding as
 *       CONNECTED);</li>
 *   <li>webhook ledger rows older than 7 days → deleted;</li>
 *   <li>media rooms of CLOSED/CANCELLED sessions with no active binding →
 *       LiveKit room deleted best-effort, outside the DB transaction.</li>
 * </ol>
 *
 * <p>Never touches attempts/answers/grades. Unbound bindings
 * ({@code attempt_id IS NULL}) are deliberately NOT terminated: the V37
 * {@code chk_bindings_unbound_still_active} constraint keeps them active
 * until an attempt binds; they simply never receive events (no attempt).
 *
 * <p>Dormant unless {@code quizopia.media.livekit.enabled=true}.
 */
@Component
public class CameraHousekeepingScheduler {

    private static final Logger log = LoggerFactory.getLogger(CameraHousekeepingScheduler.class);

    private static final Duration PREFLIGHT_EXPIRY_GRACE = Duration.ofHours(1);
    private static final Duration LEDGER_RETENTION = Duration.ofDays(7);

    private final MediaRoomGateway gateway;
    private final LiveKitProperties liveKit;
    private final MonitoringCameraQueries queries;
    private final MonitoringCameraConnectionRepository connections;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CameraHousekeepingScheduler(MediaRoomGateway gateway,
            LiveKitProperties liveKit,
            MonitoringCameraQueries queries,
            MonitoringCameraConnectionRepository connections,
            JdbcTemplate jdbc,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.liveKit = liveKit;
        this.queries = queries;
        this.connections = connections;
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${quizopia.monitoring.camera.housekeeping-interval:1h}")
    public void sweep() {
        if (!liveKit.isEnabled()) {
            return;
        }
        List<String> roomsToDelete;
        try {
            roomsToDelete = sweepNow();
        } catch (Exception e) {
            log.warn("camera housekeeping sweep failed", e);
            return;
        }
        for (String room : roomsToDelete) {
            try {
                // Media I/O outside the DB transaction (ADR-08).
                gateway.deleteRoom(room);
            } catch (TwirpMediaRoomGateway.LiveKitUnavailableException e) {
                log.warn("could not delete LiveKit room {} (will retry next sweep)", room, e);
            }
        }
    }

    private List<String> sweepNow() {
        Instant now = clock.instant();
        return transactions.execute(status -> {
            int expiredPreflights = jdbc.update(
                    "UPDATE monitoring_preflights SET status = 'EXPIRED' "
                            + "WHERE status IN ('ACTIVE', 'VERIFIED') "
                            + "AND expires_at < ?",
                    Timestamp.from(now.minus(PREFLIGHT_EXPIRY_GRACE)));
            // Select the dead bindings first, then close their OPEN camera
            // connection periods (close_reason 'CLOSED') and the bindings in
            // the SAME transaction — otherwise camera-status would keep
            // showing a terminated binding as CONNECTED.
            List<java.util.UUID> deadBindings = jdbc.queryForList(
                    "SELECT b.id FROM monitoring_media_bindings b "
                            + "WHERE b.active_to IS NULL AND b.attempt_id IS NOT NULL "
                            + "AND (EXISTS (SELECT 1 FROM exam_sessions s "
                            + "             WHERE s.id = b.exam_session_id AND s.status <> 'OPEN') "
                            + "  OR EXISTS (SELECT 1 FROM attempts a "
                            + "             WHERE a.id = b.attempt_id AND a.status <> 'IN_PROGRESS'))",
                    java.util.UUID.class);
            int closedConnections = 0;
            for (java.util.UUID bindingId : deadBindings) {
                closedConnections += connections.closeOpenRow(bindingId, "CLOSED", Timestamp.from(now));
                jdbc.update("UPDATE monitoring_media_bindings SET active_to = ? WHERE id = ?",
                        Timestamp.from(now), bindingId);
            }
            int closedBindings = deadBindings.size();
            int purgedLedger = queries.deleteLedgerOlderThan(now.minus(LEDGER_RETENTION));
            if (expiredPreflights > 0 || closedBindings > 0 || purgedLedger > 0) {
                log.info("camera housekeeping: {} preflights expired, {} bindings closed "
                        + "({} open camera periods), {} ledger rows purged",
                        expiredPreflights, closedBindings, closedConnections, purgedLedger);
            }
            return jdbc.queryForList(
                    "SELECT r.room_name FROM monitoring_media_rooms r "
                            + "JOIN exam_sessions s ON s.id = r.exam_session_id "
                            + "WHERE s.status IN ('CLOSED', 'CANCELLED') "
                            + "AND NOT EXISTS (SELECT 1 FROM monitoring_media_bindings b "
                            + "                WHERE b.exam_session_id = r.exam_session_id "
                            + "                AND b.active_to IS NULL)",
                    String.class);
        });
    }
}
