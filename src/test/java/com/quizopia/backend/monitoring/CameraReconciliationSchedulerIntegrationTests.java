package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.media.roomservice.LkParticipant;
import com.quizopia.backend.media.roomservice.LkTrack;
import com.quizopia.backend.media.roomservice.MediaRoomGateway;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;
import com.quizopia.backend.monitoring.application.CameraHousekeepingScheduler;
import com.quizopia.backend.monitoring.application.CameraReconciliationScheduler;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.cameraEventCount;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.dedupeKey;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoring;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.finishAttempt;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenCameraConnection;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertRoom;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-correction and hygiene tests for the P4-B camera schedulers (issue
 * #87): reconciliation opens/closes/touches connection periods against
 * gateway truth (both drift directions, muted cameras, gateway outage,
 * idempotence), and housekeeping expires stale preflights, closes bindings
 * of dead sessions/attempts, purges the webhook ledger and deletes rooms of
 * finished sessions — never touching attempts/answers.
 *
 * <p>The gateway is an in-memory fake (primary bean over the Twirp impl);
 * sweep intervals are stretched to an hour so the only sweeps under test are
 * the ones the tests trigger explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        CameraReconciliationSchedulerIntegrationTests.FakeGatewayConfig.class})
@TestPropertySource(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-secret=quizopia-test-livekit-webhook-signing-secret-0123456789",
        "quizopia.monitoring.camera.reconcile-interval=3600s",
        "quizopia.monitoring.camera.housekeeping-interval=3600s"
})
@Transactional
class CameraReconciliationSchedulerIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeGatewayConfig {

        @Bean
        @Primary
        static FakeMediaRoomGateway fakeMediaRoomGateway() {
            return new FakeMediaRoomGateway();
        }
    }

    /** In-memory {@link MediaRoomGateway} with test-settable state. */
    static class FakeMediaRoomGateway implements MediaRoomGateway {

        volatile List<LkParticipant> participants = List.of();
        volatile RuntimeException failure;
        final List<String> deletedRooms = new CopyOnWriteArrayList<>();

        @Override
        public Optional<LkParticipant> findParticipant(String room, String identity) {
            return participants.stream().filter(p -> p.identity().equals(identity)).findFirst();
        }

        @Override
        public List<LkParticipant> listParticipants(String room) {
            if (failure != null) {
                throw failure;
            }
            return participants;
        }

        @Override
        public boolean removeParticipant(String room, String identity) {
            return true;
        }

        @Override
        public boolean deleteRoom(String room) {
            deletedRooms.add(room);
            return true;
        }
    }

    @Autowired private CameraReconciliationScheduler reconciliation;
    @Autowired private CameraHousekeepingScheduler housekeeping;
    @Autowired private FakeMediaRoomGateway gateway;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long userId;
    private long sessionId;
    private long attemptId;
    private MonitoringCameraFixtures.PreflightBinding binding;
    private String identity;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-14T10:00:00Z");
        clock.setInstant(base);
        gateway.participants = List.of();
        gateway.failure = null;
        gateway.deletedRooms.clear();
        userId = newUser(jdbc, "recstu");
        sessionId = insertOpenSession(jdbc, userId, base);
        attemptId = attemptService.startAttempt(userId, sessionId, new StartAttemptRequest(null)).attemptId();
        enableMonitoring(jdbc, sessionId);
        insertRoom(jdbc, sessionId);
        binding = insertPreflightAndBinding(jdbc, sessionId, userId, attemptId, base, null);
        identity = binding.mediaIdentity();
    }

    // === reconciliation ===

    @Test
    void missingOpenRowIsOpenedAndEmitted() {
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", false));

        reconciliation.reconcilePeriodically();

        Map<String, Object> open = openRow();
        assertThat(open).isNotNull();
        assertThat(open.get("track_sid")).isEqualTo("TR_R1");
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        assertThat(reasonOf(attemptId, "CAMERA_CONNECTED")).isEqualTo("reconciled");
    }

    @Test
    void ghostOpenRowIsClosedAndEmitted() {
        insertOpenCameraConnection(jdbc, binding.bindingId(), "TR_R1", base.minusSeconds(60));
        gateway.participants = List.of(); // room gone / participant left

        reconciliation.reconcilePeriodically();

        assertThat(openRow()).isNull();
        Map<String, Object> closed = closedRow();
        assertThat(closed.get("close_reason")).isEqualTo("reconciled");
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isEqualTo(1);
        assertThat(reasonOf(attemptId, "CAMERA_DISCONNECTED")).isEqualTo("reconciled");
    }

    @Test
    void mutedCameraTreatedAsGone() {
        insertOpenCameraConnection(jdbc, binding.bindingId(), "TR_R1", base.minusSeconds(60));
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", true));

        reconciliation.reconcilePeriodically();

        assertThat(openRow()).isNull();
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isEqualTo(1);
    }

    @Test
    void matchingParticipantOnlyTouchesVerificationTimestamp() {
        insertOpenCameraConnection(jdbc, binding.bindingId(), "TR_R1", base.minusSeconds(120));
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", false));
        clock.advance(Duration.ofSeconds(120));

        reconciliation.reconcilePeriodically();

        Map<String, Object> open = openRow();
        assertThat(open).isNotNull();
        assertThat(((Timestamp) open.get("last_verified_at")).toInstant())
                .isEqualTo(base.plusSeconds(120));
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
    }

    @Test
    void bindingWithoutAttemptGetsRowButNoEvent() {
        jdbc.update("UPDATE monitoring_media_bindings SET attempt_id=NULL WHERE id=?",
                binding.bindingId()); // unbound ⇒ no attempt to attribute
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", false));

        reconciliation.reconcilePeriodically();

        assertThat(openRow()).isNotNull(); // row opened…
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero(); // …but no event
    }

    @Test
    void gatewayOutageSkipsSessionWithoutSideEffects() {
        gateway.failure = new TwirpMediaRoomGateway.LiveKitUnavailableException("down", null);

        reconciliation.reconcilePeriodically(); // must not throw

        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
        assertThat(connectionCount()).isZero();
    }

    @Test
    void repeatedSweepsAreEventIdempotent() {
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", false));

        reconciliation.reconcilePeriodically();
        reconciliation.reconcilePeriodically();

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        assertThat(connectionCount()).isEqualTo(1);
    }

    @Test
    void finishedAttemptGetsRowButNoEventOnOpen() {
        finishAttempt(jdbc, attemptId, base);
        gateway.participants = List.of(cameraParticipant(identity, "TR_R1", false));

        reconciliation.reconcilePeriodically();

        assertThat(openRow()).isNotNull();
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
    }

    // === housekeeping ===

    @Test
    void stalePreflightsAreExpired() {
        jdbc.update("UPDATE monitoring_preflights SET expires_at=? WHERE id=?",
                Timestamp.from(base.minusSeconds(7200)), binding.preflightId()); // 2h past
        jdbc.update("INSERT INTO monitoring_webhook_events (dedupe_key, event_type) VALUES (?, 'track_published')",
                dedupeKey("hk-old"));
        jdbc.update("UPDATE monitoring_webhook_events SET received_at=? WHERE dedupe_key=?",
                Timestamp.from(base.minusSeconds(8 * 24 * 3600)), dedupeKey("hk-old"));

        housekeeping.sweep();

        String status = jdbc.queryForObject(
                "SELECT status FROM monitoring_preflights WHERE id=?", String.class, binding.preflightId());
        assertThat(status).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_webhook_events WHERE dedupe_key=?",
                Long.class, dedupeKey("hk-old"))).isZero(); // 8-day-old ledger row purged
    }

    @Test
    void freshPreflightAndLedgerRowsSurvive() {
        jdbc.update("INSERT INTO monitoring_webhook_events (dedupe_key, event_type, received_at) "
                + "VALUES (?, 'track_published', ?)", dedupeKey("hk-fresh"), Timestamp.from(base));

        housekeeping.sweep();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_webhook_events WHERE dedupe_key=?",
                Long.class, dedupeKey("hk-fresh"))).isEqualTo(1);
        String status = jdbc.queryForObject(
                "SELECT status FROM monitoring_preflights WHERE id=?", String.class, binding.preflightId());
        assertThat(status).isEqualTo("VERIFIED"); // unexpired → untouched
    }

    @Test
    void bindingOfClosedSessionIsClosedWithoutRevoke() {
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=? WHERE id=?",
                Timestamp.from(base), sessionId);

        housekeeping.sweep();

        Map<String, Object> row = bindingRow();
        assertThat(row.get("active_to")).isNotNull();
        assertThat(row.get("revoked_at")).isNull(); // hygiene, not a teacher revoke
    }

    @Test
    void bindingOfFinishedAttemptIsClosed() {
        finishAttempt(jdbc, attemptId, base);

        housekeeping.sweep();

        assertThat(bindingRow().get("active_to")).isNotNull();
    }

    @Test
    void activeBindingOfOpenSessionWithLiveAttemptStaysActive() {
        insertOpenCameraConnection(jdbc, binding.bindingId(), "TR_R1", base);

        housekeeping.sweep();

        assertThat(bindingRow().get("active_to")).isNull();
    }

    @Test
    void roomOfClosedSessionWithoutActiveBindingsIsDeleted() {
        jdbc.update("UPDATE monitoring_media_bindings SET active_to=? WHERE id=?",
                Timestamp.from(base), binding.bindingId());
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=? WHERE id=?",
                Timestamp.from(base), sessionId);
        String roomName = jdbc.queryForObject(
                "SELECT room_name FROM monitoring_media_rooms WHERE exam_session_id=?",
                String.class, sessionId);

        housekeeping.sweep();

        assertThat(gateway.deletedRooms).containsExactly(roomName);
    }

    @Test
    void roomOfOpenSessionIsNeverDeleted() {
        housekeeping.sweep();

        assertThat(gateway.deletedRooms).isEmpty();
    }

    // === helpers ===

    private static LkParticipant cameraParticipant(String identity, String sid, boolean muted) {
        return new LkParticipant(identity, "ACTIVE",
                List.of(new LkTrack(sid, "VIDEO", "CAMERA", muted)));
    }

    private String reasonOf(long attempt, String type) {
        return jdbc.queryForObject(
                "SELECT metadata->>'reason' FROM attempt_activity_events WHERE attempt_id=? AND event_type=?",
                String.class, attempt, type);
    }

    private long connectionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_camera_connections WHERE binding_id=?",
                Long.class, binding.bindingId());
    }

    private Map<String, Object> openRow() {
        return jdbc.queryForList("SELECT * FROM monitoring_camera_connections "
                        + "WHERE binding_id=? AND disconnected_at IS NULL", binding.bindingId())
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> closedRow() {
        return jdbc.queryForList("SELECT * FROM monitoring_camera_connections "
                        + "WHERE binding_id=? AND disconnected_at IS NOT NULL", binding.bindingId())
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> bindingRow() {
        return jdbc.queryForMap("SELECT * FROM monitoring_media_bindings WHERE id=?", binding.bindingId());
    }
}
