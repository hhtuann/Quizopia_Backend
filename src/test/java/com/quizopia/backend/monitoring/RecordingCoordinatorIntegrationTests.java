package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertRoom;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.quizopia.backend.evidence.FakeEgressGateway;
import com.quizopia.backend.evidence.application.EvidenceProperties;
import com.quizopia.backend.evidence.repository.MonitoringRecordingJobRepository;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.media.storage.EvidenceStorage.ObjectHead;
import com.quizopia.backend.monitoring.application.LiveKitWebhookService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P4-D recording coordinator (issues #92/#93, ADR-009 §2.2/§2.4) against the
 * real Flyway schema + real webhook routing. Not {@code @Transactional}:
 * the coordinator listens AFTER COMMIT, so every {@code handle()} runs in
 * its own committed transaction (the production webhook shape).
 *
 * <p>Matrix: policy FULL/ROLLING/OFF; single-run dedupe; disconnect stop;
 * egress COMPLETE with file (+ storage HEAD verify) incl. rolling pulse
 * chaining and keep-N cleanup; egress FAILED honesty; storage/egress
 * failures never throw into the webhook path.
 */
@SpringBootTest(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-secret=quizopia-test-livekit-webhook-signing-secret-0123456789",
        "quizopia.monitoring.camera.reconcile-enabled=false",
        "quizopia.monitoring.camera.housekeeping-interval=3600s"
})
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        RecordingCoordinatorIntegrationTests.EgressTestConfig.class})
class RecordingCoordinatorIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class EgressTestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        FakeEgressGateway fakeEgressGateway() {
            return new FakeEgressGateway();
        }
    }

    @Autowired private LiveKitWebhookService webhookService;
    @Autowired private FakeEgressGateway egress;
    @Autowired private com.quizopia.backend.evidence.application.RecordingCoordinator coordinator;
    @Autowired private MonitoringRecordingJobRepository jobs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EvidenceProperties properties;
    @Autowired private TransactionTemplate tx;
    @MockitoBean private EvidenceStorage storage;

    private Instant base;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private UUID bindingId;
    private String roomName;
    private String trackSid;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "recstudent-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, studentId, base);
        attemptId = jdbc.queryForObject(
                "INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, "
                        + "started_at, deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?) RETURNING id",
                Long.class, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base.plusSeconds(3600)));
        var pb = insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
        bindingId = pb.bindingId();
        roomName = insertRoom(jdbc, sessionId);
        trackSid = "TR_CAMERA_1";
        // the shared fake accumulates across tests — reset per test
        egress.started.clear();
        egress.stopped.clear();
        egress.infos.clear();
        egress.startFailure = null;
        egress.stopFailure = null;
        // storage HEAD says every egress file exists
        when(storage.head(anyString())).thenReturn(Optional.of(new ObjectHead(1234)));
    }


    /** Coordinator work is serialized onto its daemon executor — await it
     *  (generous budget: shared CI runners are slow). */
    private void awaitTrue(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 300 && !condition.getAsBoolean(); i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void publishCamera() {
        tx.executeWithoutResult(s -> webhookService.handle(cameraPublishedEvent()));
    }

    private com.quizopia.backend.media.webhook.LiveKitWebhookEvent cameraPublishedEvent() {
        return new com.quizopia.backend.media.webhook.LiveKitWebhookEvent(
                "track_published", roomName, mediaIdentity(), "ACTIVE", trackSid,
                "VIDEO", "CAMERA", base, MonitoringCameraFixtures.dedupeKey(UUID.randomUUID().toString()),
                null, null, 0, 0, null, List.of());
    }

    private String mediaIdentity() {
        return jdbc.queryForObject(
                "SELECT media_identity FROM monitoring_media_bindings WHERE id = ?",
                String.class, bindingId);
    }

    /** Latest job row selected via the fake gateway's monotonic ids —
     *  created_at ties (frozen test clock) make timestamp ordering useless. */
    private Map<String, Object> latestJob() {
        String egressId = egress.started.get(egress.started.size() - 1).split("\\|")[0];
        return jdbc.queryForMap(
                "SELECT kind, status, egress_id, object_key, bytes FROM monitoring_recording_jobs "
                        + "WHERE egress_id = ?", egressId);
    }

    private String latestEgressId() {
        return egress.started.get(egress.started.size() - 1).split("\\|")[0];
    }

    private long activityCount(String type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? AND event_type = ?",
                Long.class, attemptId, type);
        return count == null ? 0 : count;
    }

    @Test
    void recordingOnPolicyStartsOneFullRunOnCameraPublish() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, false);
        publishCamera();
        publishCamera(); // duplicate publish must not start a second run
        awaitTrue(() -> !egress.started.isEmpty() && activityCount("RECORDING_STARTED") >= 1);

        assertThat(egress.started).hasSize(1);
        Map<String, Object> job = latestJob();
        assertThat(job.get("kind")).isEqualTo("FULL");
        assertThat(job.get("status")).isIn("STARTING", "ACTIVE");
        assertThat(activityCount("RECORDING_STARTED")).isEqualTo(1);
    }

    @Test
    void clipsOnRecordingOffPolicyStartsRollingPulse() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());

        assertThat(egress.started).hasSize(1);
        Map<String, Object> job = latestJob();
        assertThat(job.get("kind")).isEqualTo("ROLLING");
        assertThat(activityCount("RECORDING_STARTED")).isEqualTo(1);
    }

    @Test
    void monitoringOffPolicyNeverStartsAnything() {
        publishCamera();
        awaitTrue(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE attempt_id = ?",
                Long.class, attemptId) != 0);
        assertThat(egress.started).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE attempt_id = ?",
                Long.class, attemptId)).isZero();
    }

    @Test
    void cameraTerminationStopsActiveRun() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, false);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());
        String egressId = (String) latestJob().get("egress_id");

        tx.executeWithoutResult(s -> webhookService.handle(
                new com.quizopia.backend.media.webhook.LiveKitWebhookEvent(
                        "track_unpublished", roomName, mediaIdentity(), null, trackSid,
                        "VIDEO", "CAMERA", base.plusSeconds(30),
                        MonitoringCameraFixtures.dedupeKey(UUID.randomUUID().toString()),
                        null, null, 0, 0, null, List.of())));
        awaitTrue(() -> !egress.stopped.isEmpty());

        assertThat(egress.stopped).containsExactly(egressId);
        assertThat(latestJob().get("status")).isEqualTo("ENDING");
    }

    @Test
    void egressCompleteStoresObjectMetadataAndChainsRollingPulseWithKeepN() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());
        String firstEgress = (String) latestJob().get("egress_id");

        // Complete pulse 0 with a real file result.
        egress.completeWithFile(firstEgress, "s3://quizopia-evidence/rolling/" + attemptId
                + "/rolling-0-1.mp4", 65_536, 120_000_000_000L,
                base.getEpochSecond() * 1_000_000_000L,
                base.plusSeconds(120).getEpochSecond() * 1_000_000_000L);
        tx.executeWithoutResult(s -> webhookService.handle(egressEndedEvent(firstEgress)));
        awaitTrue(() -> egress.started.size() >= 2);

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, object_key, bytes, duration_ms FROM monitoring_recording_jobs "
                        + "WHERE egress_id = ?", firstEgress);
        assertThat(job.get("status")).isEqualTo("COMPLETE");
        assertThat(job.get("object_key")).isEqualTo(
                "rolling/" + attemptId + "/rolling-0-1.mp4");
        assertThat(((Number) job.get("bytes")).longValue()).isEqualTo(65_536L);
        assertThat(((Number) job.get("duration_ms")).longValue()).isEqualTo(120_000L);
        assertThat(activityCount("RECORDING_STOPPED")).isEqualTo(1);
        // camera still open + attempt IN_PROGRESS → next pulse chained
        assertThat(egress.started).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE kind = 'ROLLING' AND attempt_id = ?",
                Long.class, attemptId)).isEqualTo(2L);
    }

    @Test
    void keepNDeletesOlderPulseObjectsButKeepsGapRows() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);
        publishCamera();
        // Seed keep+1 completed pulse rows directly (older than the live one).
        for (int i = 0; i <= properties.getRollingKeep(); i++) {
            jdbc.update("INSERT INTO monitoring_recording_jobs "
                    + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                    + "track_sid, status, pulse_index, started_at, ended_at, object_key, bytes, duration_ms) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,60000)",
                    UUID.randomUUID(), sessionId, attemptId, bindingId, "ROLLING",
                    "EG_SEED_" + i, roomName, trackSid, "COMPLETE", i,
                    java.sql.Timestamp.from(base.plusSeconds(i * 120L)),
                    java.sql.Timestamp.from(base.plusSeconds(i * 120L + 100)),
                    "rolling/" + attemptId + "/seed-" + i + ".mp4");
        }
        awaitTrue(() -> !egress.started.isEmpty());
        // trigger cleanup by completing the newest (live) pulse
        String live = latestEgressId();
        egress.completeWithFile(live, "rolling/" + attemptId + "/live.mp4", 10, 1,
                base.getEpochSecond(), base.plusSeconds(60).getEpochSecond());
        tx.executeWithoutResult(s -> webhookService.handle(egressEndedEvent(live)));
        awaitTrue(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE object_deleted = true",
                Long.class) >= 1);

        // seed rows beyond keep-N are marked object_deleted and the objects deleted
        Long deletedFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE object_deleted = true",
                Long.class);
        assertThat(deletedFlags).isGreaterThanOrEqualTo(1L);
        // rows survive as gap evidence (never physically removed)
        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_recording_jobs WHERE attempt_id = ?", Long.class, attemptId);
        assertThat(rowCount).isGreaterThanOrEqualTo(properties.getRollingKeep() + 1L);
    }



    @Test
    void egressFailedIsHonestNeverSilent() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, false);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());
        String egressId = (String) latestJob().get("egress_id");
        egress.markFailed(egressId, "no keyframe");
        tx.executeWithoutResult(s -> webhookService.handle(egressEndedEvent(egressId)));
        awaitTrue(() -> "FAILED".equals(jdbc.queryForObject(
                "SELECT status FROM monitoring_recording_jobs WHERE egress_id = ?",
                String.class, egressId)));

        assertThat(latestJob().get("status")).isEqualTo("FAILED");
        assertThat(activityCount("RECORDING_FAILED")).isEqualTo(1);
    }

    @Test
    void missingStorageObjectMeansNoObjectKeyEverRecorded() {
        when(storage.head(anyString())).thenReturn(Optional.empty());
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, false);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());
        String egressId = (String) latestJob().get("egress_id");
        egress.completeWithFile(egressId, "recordings/" + attemptId + "/x.mp4", 5, 1, 1, 2);
        tx.executeWithoutResult(s -> webhookService.handle(egressEndedEvent(egressId)));
        awaitTrue(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM monitoring_recording_jobs WHERE egress_id = ?",
                    String.class, egressId);
            return "COMPLETE".equals(status) || "ENDING".equals(status);
        });

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, object_key FROM monitoring_recording_jobs WHERE egress_id = ?",
                egressId);
        assertThat(job.get("object_key")).isNull();
    }

    @Test
    void pulseTimeoutStopsRunningRollingPulse() {
        enableMonitoringWithPolicy(jdbc, sessionId, false, false, true);
        publishCamera();
        awaitTrue(() -> !egress.started.isEmpty());
        String egressId = latestEgressId();

        // the scheduled watchdog firing while the pulse is still active
        coordinator.pulseTimeout(egressId);

        assertThat(egress.stopped).contains(egressId);
        assertThat(latestJob().get("status")).isEqualTo("ENDING");
    }

    @Test
    void egressStartFailureNeverBreaksTheWebhookPath() {
        egress.startFailure = new RuntimeException("boom");
        enableMonitoringWithPolicy(jdbc, sessionId, false, true, false);
        assertThatCode(this::publishCamera).doesNotThrowAnyException();
        awaitTrue(() -> activityCount("RECORDING_FAILED") >= 1);
        assertThat(activityCount("RECORDING_FAILED")).isEqualTo(1);
        assertThat(egress.stopped).isEmpty();
    }

    private com.quizopia.backend.media.webhook.LiveKitWebhookEvent egressEndedEvent(String egressId) {
        return new com.quizopia.backend.media.webhook.LiveKitWebhookEvent(
                "egress_ended", roomName, null, null, null, null, null,
                base.plusSeconds(121), MonitoringCameraFixtures.dedupeKey(UUID.randomUUID().toString()),
                egressId, egress.infos.containsKey(egressId)
                        ? egress.infos.get(egressId).status() : "EGRESS_COMPLETE",
                egress.infos.get(egressId).startedAtNs(), egress.infos.get(egressId).endedAtNs(),
                egress.infos.get(egressId).error(),
                egress.infos.get(egressId).fileResults().stream()
                        .map(f -> new com.quizopia.backend.media.webhook.LiveKitWebhookEvent.EgressFile(
                                f.filename(), f.size(), f.durationNs(), f.startedAtNs(), f.endedAtNs()))
                        .toList());
    }
}
