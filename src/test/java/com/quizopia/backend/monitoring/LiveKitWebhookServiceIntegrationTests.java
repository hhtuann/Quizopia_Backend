package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.media.webhook.LiveKitWebhookEvent;
import com.quizopia.backend.monitoring.application.LiveKitWebhookService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.cameraEventCount;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.dedupeKey;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoring;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.finishAttempt;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertClosedCameraConnection;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenCameraConnection;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertRoom;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service-level routing tests for verified LiveKit webhook events (P4-B4,
 * issue #87): ledger-first idempotency, one-open-row semantics,
 * CONNECTED/DISCONNECTED/RECONNECTED transitions (reconnect window, gap
 * seconds), pre-attempt/finished-attempt suppression, late-webhook
 * non-reattribution across attempts (opaque per-preflight identities), and
 * ledger-only routing for unknown identities / non-camera tracks / unrelated
 * event types.
 *
 * <p>Events are constructed directly (the verifier is lead-owned and covered
 * by the controller slice test); clock-driven timestamps are deterministic
 * via {@link MutableClock}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@TestPropertySource(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-secret=quizopia-test-livekit-webhook-signing-secret-0123456789",
        "quizopia.monitoring.camera.reconcile-enabled=false",
        "quizopia.monitoring.camera.housekeeping-interval=3600s"
})
@Transactional
class LiveKitWebhookServiceIntegrationTests {

    @Autowired private LiveKitWebhookService webhookService;
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
        base = Instant.parse("2026-11-14T09:00:00Z");
        clock.setInstant(base);
        userId = newUser(jdbc, "camstu");
        sessionId = insertOpenSession(jdbc, userId, base);
        attemptId = attemptService.startAttempt(userId, sessionId, new StartAttemptRequest(null)).attemptId();
        enableMonitoring(jdbc, sessionId);
        insertRoom(jdbc, sessionId);
        binding = insertPreflightAndBinding(jdbc, sessionId, userId, attemptId, base, null);
        identity = binding.mediaIdentity();
    }

    // === track_published (camera) ===

    @Test
    void cameraPublishedOpensConnectionAndEmitsServerEvent() {
        webhookService.handle(published("TR_1", "pub-1"));

        Map<String, Object> open = openRow();
        assertThat(open).isNotNull();
        assertThat(open.get("track_sid")).isEqualTo("TR_1");
        assertThat(open.get("disconnected_at")).isNull();

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        Map<String, Object> event = eventRow(attemptId, "CAMERA_CONNECTED");
        assertThat(event.get("source")).isEqualTo("SERVER");
        assertThat(event.get("reason")).isEqualTo("webhook");
        assertThat(event.get("track_sid")).isEqualTo("TR_1");
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void duplicateDeliveryIsExactNoOp() {
        webhookService.handle(published("TR_1", "dup"));
        webhookService.handle(published("TR_1", "dup"));

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        assertThat(connectionRowCount()).isEqualTo(1);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void retransmissionWithFreshEventIdRefreshesRowWithoutSecondEvent() {
        webhookService.handle(published("TR_1", "retx-1"));
        webhookService.handle(published("TR_2", "retx-2"));

        assertThat(connectionRowCount()).isEqualTo(1);
        assertThat(openRow().get("track_sid")).isEqualTo("TR_2");
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
    }

    @Test
    void preAttemptPublishRecordsRowOnly() {
        long otherUser = newUser(jdbc, "prestu");
        long otherSession = insertOpenSession(jdbc, otherUser, base);
        enableMonitoring(jdbc, otherSession);
        insertRoom(jdbc, otherSession);
        MonitoringCameraFixtures.PreflightBinding unbound =
                insertPreflightAndBinding(jdbc, otherSession, otherUser, null, base, null);

        webhookService.handle(publishedFor(unbound.mediaIdentity(), "TR_1", "pre-1"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_camera_connections WHERE binding_id=?",
                Long.class, unbound.bindingId())).isEqualTo(1); // presence bookkeeping exists…
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero(); // …but no attempt → no event
    }

    @Test
    void finishedAttemptGetsRowButNoEvent() {
        finishAttempt(jdbc, attemptId, base);
        webhookService.handle(published("TR_1", "fin-1"));

        assertThat(connectionRowCount()).isEqualTo(1);
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
    }

    // === termination ===

    @Test
    void participantLeftClosesRowAndEmitsDisconnected() {
        webhookService.handle(published("TR_1", "disc-1"));
        webhookService.handle(left("disc-2"));

        Map<String, Object> closed = openRow();
        assertThat(closed).isNull();
        Map<String, Object> row = closedRow();
        assertThat(row.get("close_reason")).isEqualTo("participant_left");
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isEqualTo(1);
        Map<String, Object> event = eventRow(attemptId, "CAMERA_DISCONNECTED");
        assertThat(event.get("reason")).isEqualTo("participant_left");
        assertThat(event.get("track_sid")).isNull(); // participant_left carries no track
    }

    @Test
    void duplicateTerminationIsNoOp() {
        webhookService.handle(published("TR_1", "dupd-1"));
        webhookService.handle(left("dupd-2"));
        webhookService.handle(left("dupd-2")); // exact duplicate → ledger skip

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isEqualTo(1);
        assertThat(closedRow().get("close_reason")).isEqualTo("participant_left");
    }

    @Test
    void reconnectionWithinWindowEmitsReconnectedWithGap() {
        webhookService.handle(published("TR_1", "rec-1"));
        webhookService.handle(left("rec-2"));
        clock.advance(Duration.ofMinutes(5));
        webhookService.handle(published("TR_2", "rec-3"));

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1); // initial connect only
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_RECONNECTED")).isEqualTo(1);
        Map<String, Object> event = eventRow(attemptId, "CAMERA_RECONNECTED");
        assertThat(event.get("reason")).isEqualTo("webhook");
        assertThat(event.get("track_sid")).isEqualTo("TR_2");
        assertThat(event.get("gap_seconds")).isEqualTo("300");
        assertThat(connectionRowCount()).isEqualTo(2); // closed period + new open period
        assertThat(openRow()).isNotNull();
    }

    @Test
    void reconnectionAfterLongGapEmitsPlainConnected() {
        webhookService.handle(published("TR_1", "long-1"));
        webhookService.handle(left("long-2"));
        clock.advance(Duration.ofMinutes(45));
        webhookService.handle(published("TR_2", "long-3"));

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_RECONNECTED")).isZero();
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(2); // initial + post-gap
    }

    @Test
    void nonCameraTrackUnpublishedIsLedgerOnly() {
        webhookService.handle(published("TR_1", "mic-1"));
        webhookService.handle(unpublished("TR_1", "AUDIO", "MICROPHONE", "mic-2"));

        assertThat(openRow()).isNotNull();
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isZero();
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void terminationOfDifferentTrackSidIsIgnored() {
        webhookService.handle(published("TR_1", "mism-1"));
        webhookService.handle(unpublished("TR_OTHER", "VIDEO", "CAMERA", "mism-2"));

        assertThat(openRow()).isNotNull();
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_DISCONNECTED")).isZero();
    }

    @Test
    void terminationWithoutOpenRowIsLedgerOnly() {
        webhookService.handle(left("late-1"));

        assertThat(connectionRowCount()).isZero();
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
        assertThat(ledgerCount()).isEqualTo(1);
    }

    // === identity semantics ===

    @Test
    void unknownIdentityIsLedgerOnly() {
        webhookService.handle(publishedFor("ghost-identity", "TR_1", "unk-1"));

        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(connectionRowCount()).isZero();
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
    }

    @Test
    void lateTerminationForClosedBindingNeverTouchesNextAttempt() {
        // Dedicated session: both attempts start BEFORE monitoring is enabled,
        // so the P4-B start gate never interferes with this routing test.
        long user = newUser(jdbc, "latestu");
        long session = insertOpenSession(jdbc, user, base);
        long attempt1 = attemptService.startAttempt(user, session, new StartAttemptRequest(null)).attemptId();
        finishAttempt(jdbc, attempt1, base);
        long attempt2 = attemptService.startAttempt(user, session, new StartAttemptRequest(null)).attemptId();
        enableMonitoring(jdbc, session);
        insertRoom(jdbc, session);

        // Attempt 1 (identity A): finished, binding terminated, period closed.
        MonitoringCameraFixtures.PreflightBinding old =
                insertPreflightAndBinding(jdbc, session, user, attempt1, base, base);
        insertClosedCameraConnection(jdbc, old.bindingId(), "TR_OLD",
                base.minusSeconds(600), base.minusSeconds(300));

        // Attempt 2 (identity B): fresh preflight/binding, in progress.
        MonitoringCameraFixtures.PreflightBinding current =
                insertPreflightAndBinding(jdbc, session, user, attempt2, base, null);
        insertOpenCameraConnection(jdbc, current.bindingId(), "TR_NEW", base);

        // Late webhooks still carry the OLD identity.
        webhookService.handle(publishedFor(old.mediaIdentity(), "TR_1", "latepub-1"));
        webhookService.handle(leftOf(old.mediaIdentity(), "latedisc-1"));

        assertThat(cameraEventCount(jdbc, attempt2, "CAMERA_CONNECTED")).isZero();
        assertThat(cameraEventCount(jdbc, attempt2, "CAMERA_DISCONNECTED")).isZero();
        assertThat(cameraEventCount(jdbc, attempt2, "CAMERA_RECONNECTED")).isZero();
        assertThat(openRowOf(current.bindingId())).isNotNull(); // attempt 2 period untouched
        assertThat(ledgerCount()).isEqualTo(2); // both deliveries ledger-accepted
    }

    // === unrelated event types ===

    @Test
    void roomLifecycleEventsAreLedgerOnly() {
        webhookService.handle(new LiveKitWebhookEvent("room_started", "room-x", null, null,
                null, null, null, base, dedupeKey("room-1")));
        webhookService.handle(new LiveKitWebhookEvent("egress_started", "room-x", null, null,
                null, null, null, base, dedupeKey("egress-1")));
        webhookService.handle(new LiveKitWebhookEvent("participant_joined", "room-x", "teacher-1",
                "ACTIVE", null, null, null, base, dedupeKey("join-1")));

        assertThat(ledgerCount()).isEqualTo(3);
        assertThat(connectionRowCount()).isZero();
        assertThat(MonitoringCameraFixtures.cameraEventTotal(jdbc)).isZero();
    }

    // === helpers ===

    private LiveKitWebhookEvent published(String trackSid, String tag) {
        return publishedFor(identity, trackSid, tag);
    }

    private LiveKitWebhookEvent publishedFor(String participantIdentity, String trackSid, String tag) {
        return new LiveKitWebhookEvent("track_published", "room-x", participantIdentity, "ACTIVE",
                trackSid, "VIDEO", "CAMERA", base, dedupeKey(tag));
    }

    private LiveKitWebhookEvent left(String tag) {
        return leftOf(identity, tag);
    }

    private LiveKitWebhookEvent leftOf(String participantIdentity, String tag) {
        return new LiveKitWebhookEvent("participant_left", "room-x", participantIdentity, "DISCONNECTED",
                null, null, null, base, dedupeKey(tag));
    }

    private LiveKitWebhookEvent unpublished(String trackSid, String type, String source, String tag) {
        return new LiveKitWebhookEvent("track_unpublished", "room-x", identity, "ACTIVE",
                trackSid, type, source, base, dedupeKey(tag));
    }

    private long ledgerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_webhook_events", Long.class);
    }

    private long connectionRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_camera_connections WHERE binding_id=?",
                Long.class, binding.bindingId());
    }

    private Map<String, Object> openRow() {
        return rowWhere("disconnected_at IS NULL");
    }

    private Map<String, Object> closedRow() {
        return rowWhere("disconnected_at IS NOT NULL");
    }

    private Map<String, Object> openRowOf(UUID bindingId) {
        return jdbc.queryForList("SELECT * FROM monitoring_camera_connections "
                        + "WHERE binding_id=? AND disconnected_at IS NULL", bindingId)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> rowWhere(String predicate) {
        return jdbc.queryForList("SELECT * FROM monitoring_camera_connections "
                + "WHERE binding_id=? AND " + predicate, binding.bindingId())
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> eventRow(long attempt, String type) {
        return jdbc.queryForList("SELECT source, metadata->>'reason' AS reason, "
                        + "metadata->>'trackSid' AS track_sid, metadata->>'gapSeconds' AS gap_seconds "
                        + "FROM attempt_activity_events WHERE attempt_id=? AND event_type=?",
                        attempt, type)
                .get(0);
    }
}
