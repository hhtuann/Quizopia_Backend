package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.evidence.application.EvidenceSnapshotService;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MinioTestContainerConfiguration;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * D1 (issue #91) snapshots on the REAL stack: real MinIO container
 * (PUT/HEAD round-trip, private bucket), real ingest→snapshotRequest
 * flow, idempotency, bounds and the ownership matrix. Provenance honesty:
 * every stored row is CLIENT_CAPTURE — the server labels what it received,
 * never more.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        MinioTestContainerConfiguration.class})
class EvidenceSnapshotIntegrationTests {

    /** 1×1 real JPEG (SOI…EOI) — tiny but a genuine JPEG byte stream. */
    private static final byte[] REAL_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
                    + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
                    + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==");

    @Autowired private AiDetectionIngestService ingest;
    @Autowired private AttemptService attemptService;
    @Autowired private EvidenceSnapshotService snapshots;
    @Autowired private EvidenceStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private com.quizopia.backend.evidence.application.EvidenceProperties properties;

    private Instant base;
    private long studentId;
    private long otherStudentId;
    private long sessionId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "snapstudent-" + UUID.randomUUID().toString().substring(0, 8));
        otherStudentId = newUser(jdbc, "snapother-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, studentId, base);
        // Start BEFORE any policy exists — the monitored start gate would
        // otherwise demand a readiness lease (fixture convention).
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        enableMonitoringWithPolicy(jdbc, sessionId, true, false, false);
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
    }

    private long openAlertId() {
        AiDetectionBatchResponse r = ingest.ingestStudent(studentId, attemptId,
                batch(phoneAt(base.minusSeconds(1), 1)));
        assertThat(r.acceptedCount()).isEqualTo(1);
        var request = r.results().get(0).snapshotRequest();
        assertThat(request).as("ingest asks for a snapshot when the policy enables it")
                .isNotNull();
        return request.alertId();
    }

    @Test
    void ingestWithoutSnapshotPolicyNeverRequestsSnapshots() {
        // snapshots OFF for this session's policy (rows are 1:1; this test's
        // fixture flips the existing row)
        jdbc.update("UPDATE session_monitoring_policies SET evidence_snapshots_enabled = false "
                + "WHERE exam_session_id = ?", sessionId);
        AiDetectionBatchResponse r = ingest.ingestStudent(studentId, attemptId,
                batch(phoneAt(base.minusSeconds(1), 1)));
        assertThat(r.results().get(0).snapshotRequest()).isNull();
    }

    @Test
    void uploadStoresRealObjectWithSha256AndHeadVerifiesIt() {
        long alertId = openAlertId();
        var outcome = snapshots.upload(studentId, attemptId, alertId,
                "idem-" + UUID.randomUUID(), base.toEpochMilli(), REAL_JPEG);
        assertThat(outcome.owner()).isTrue();

        assertThat(outcome.row().getStatus()).isEqualTo("AVAILABLE");
        assertThat(outcome.row().getProvenance()).isEqualTo("CLIENT_CAPTURE");
        var row = jdbc.queryForMap(
                "SELECT object_key, sha256, bytes, provenance, status FROM monitoring_evidence "
                        + "WHERE id = ?", outcome.row().getId());
        String key = (String) row.get("object_key");
        assertThat(key).startsWith("snapshots/" + attemptId + "/");
        assertThat(row.get("provenance")).isEqualTo("CLIENT_CAPTURE");
        // REAL object storage round-trip
        assertThat(storage.head(key)).isPresent();
        assertThat(storage.head(key).get().bytes()).isEqualTo(REAL_JPEG.length);
        assertThat(row.get("sha256")).isEqualTo(EvidenceSnapshotService.sha256Hex(REAL_JPEG));
        // activity + alert linkage
        Long uploaded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id=? "
                        + "AND event_type='EVIDENCE_SNAPSHOT_UPLOADED'", Long.class, attemptId);
        assertThat(uploaded).isEqualTo(1L);
    }

    @Test
    void duplicateIdempotencyKeyReplaysTheFirstRowWithoutASecondObject() {
        long alertId = openAlertId();
        String key = "idem-" + UUID.randomUUID();
        var first = snapshots.upload(studentId, attemptId, alertId,
                key, base.toEpochMilli(), REAL_JPEG);
        var retry = snapshots.upload(studentId, attemptId, alertId,
                key, base.plusSeconds(2).toEpochMilli(), REAL_JPEG);
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.row().getId()).isEqualTo(first.row().getId());
        // no overwrite: the winner's digest/bytes/status survive the replay
        assertThat(retry.row().getSha256()).isEqualTo(first.row().getSha256());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE kind='SNAPSHOT' AND attempt_id = ?",
                Long.class, attemptId)).isEqualTo(1L);
    }

    @Test
    void perAlertBudgetIsEnforcedAfterThreeSnapshots() {
        long alertId = openAlertId();
        for (int i = 0; i < 3; i++) {
            snapshots.upload(studentId, attemptId, alertId, "idem-" + i,
                    base.plusSeconds(i).toEpochMilli(), REAL_JPEG);
        }
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                "idem-over", base.plusSeconds(9).toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_SNAPSHOT_REJECTED"));
        Long rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id=? "
                        + "AND event_type='EVIDENCE_SNAPSHOT_REJECTED'", Long.class, attemptId);
        assertThat(rejected).isEqualTo(1L);
    }

    @Test
    void oversizedPayloadRejectedByServiceCap() {
        // SERVICE layer: the 2 MiB cap rejects a 3.5 MiB payload regardless
        // of transport (renamed from the earlier endpoint claim — the
        // transport envelope itself is asserted by the MockMvc test below).
        long alertId = openAlertId();
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                "idem-big", base.toEpochMilli(), new byte[3_500_000]))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isIn("EVIDENCE_SNAPSHOT_REJECTED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE kind='SNAPSHOT' AND attempt_id = ?",
                Long.class, attemptId)).isZero();
    }

    @Test
    void oversizedPartRejectedByEndpointCapNotMultipartLayer() throws Exception {
        // ENDPOINT + TRANSPORT layer (P2-3 regression guard): a 3.5 MiB part
        // TRAVERSES the multipart envelope (still 5 MiB file / 6 MiB request —
        // the pre-P4-D workbook limits restored) and is rejected by the
        // controller's own pre-guard, not by the transport.
        long alertId = openAlertId();
        byte[] part = new byte[3_500_000];
        part[0] = (byte) 0xFF;
        part[1] = (byte) 0xD8;
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/attempts/{id}/evidence/snapshots", attemptId)
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "snap.jpg", "image/jpeg", part))
                        .param("alertId", String.valueOf(alertId))
                        .param("idempotencyKey", "idem-endpoint")
                        .param("capturedAt", String.valueOf(base.toEpochMilli()))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.claim("sub", String.valueOf(studentId)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE kind='SNAPSHOT' AND attempt_id = ?",
                Long.class, attemptId)).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.web.context.WebApplicationContext context;

    @Test
    void replayAfterQuotaFullReturnsOriginalRecord() {
        long alertId = openAlertId();
        String key = "idem-quota-replay-" + UUID.randomUUID();
        var first = snapshots.upload(studentId, attemptId, alertId, key,
                base.toEpochMilli(), REAL_JPEG);
        assertThat(first.owner()).isTrue();
        for (int i = 1; i < properties.getSnapshotPerAlert(); i++) {
            snapshots.upload(studentId, attemptId, alertId, "idem-fill-" + key + "-" + i,
                    base.plusSeconds(i).toEpochMilli(), REAL_JPEG);
        }
        // retry the SAME key with 3/alert FULL → original record back
        var replay = snapshots.upload(studentId, attemptId, alertId, key,
                base.plusSeconds(30).toEpochMilli(), REAL_JPEG);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.row().getId()).isEqualTo(first.row().getId());
        assertThat(replay.row().getStatus()).isEqualTo("AVAILABLE");
        assertThat(replay.row().getSha256()).isEqualTo(first.row().getSha256());
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                "idem-over-quota-" + key, base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class);
    }

    @Test
    void sameKeySameAttemptDifferentAlertIsConflict() {
        long alertId = openAlertId();
        String key = "idem-alert-conflict-" + UUID.randomUUID();
        snapshots.upload(studentId, attemptId, alertId, key, base.toEpochMilli(), REAL_JPEG);
        long otherAlert = jdbc.queryForObject(
                "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                        + "alert_type, status, first_detected_at, last_detected_at, detection_count, "
                        + "model_id, model_version, provenance) "
                        + "VALUES (?,?,?,'SCREEN_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, attemptId, sessionId, studentId,
                java.sql.Timestamp.from(base.plusSeconds(10)),
                java.sql.Timestamp.from(base.plusSeconds(12)));
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, otherAlert,
                key, base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_SNAPSHOT_REJECTED"));
        var row = jdbc.queryForMap(
                "SELECT alert_id, status FROM monitoring_evidence WHERE idempotency_key = ?", key);
        assertThat(((Number) row.get("alert_id")).longValue()).isEqualTo(alertId);
        assertThat(row.get("status")).isEqualTo("AVAILABLE");
    }

    @Test
    void sameKeySameAlertDifferentPayloadIsConflict() {
        long alertId = openAlertId();
        String key = "idem-payload-conflict-" + UUID.randomUUID();
        snapshots.upload(studentId, attemptId, alertId, key, base.toEpochMilli(), REAL_JPEG);
        byte[] differentJpeg = new byte[REAL_JPEG.length + 64];
        System.arraycopy(REAL_JPEG, 0, differentJpeg, 0, REAL_JPEG.length);
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                key, base.toEpochMilli(), differentJpeg))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_SNAPSHOT_REJECTED"));
        var row = jdbc.queryForMap(
                "SELECT sha256, status FROM monitoring_evidence WHERE idempotency_key = ?", key);
        assertThat(row.get("sha256")).isEqualTo(EvidenceSnapshotService.sha256Hex(REAL_JPEG));
    }

    @Test
    void concurrentSameKeyAcrossDifferentAlertsSingleOwnerPerKey() throws Exception {
        long alertA = openAlertId();
        long alertB = jdbc.queryForObject(
                "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                        + "alert_type, status, first_detected_at, last_detected_at, detection_count, "
                        + "model_id, model_version, provenance) "
                        + "VALUES (?,?,?,'SCREEN_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, attemptId, sessionId, studentId,
                java.sql.Timestamp.from(base.plusSeconds(20)),
                java.sql.Timestamp.from(base.plusSeconds(22)));
        String key = "idem-multi-alert-" + UUID.randomUUID();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<Future<Object>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final long alert = (i % 2 == 0) ? alertA : alertB;
            futures.add(pool.submit(() -> {
                start.await();
                return snapshots.upload(studentId, attemptId, alert, key,
                        base.toEpochMilli(), REAL_JPEG);
            }));
        }
        start.countDown();
        int owners = 0;
        int replays = 0;
        int conflicts = 0;
        java.util.List<java.util.UUID> rowIds = new java.util.ArrayList<>();
        for (Future<Object> future : futures) {
            try {
                EvidenceSnapshotService.UploadOutcome outcome =
                        (EvidenceSnapshotService.UploadOutcome) future.get(
                                30, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome.owner()) {
                    owners++;
                } else {
                    replays++;
                }
                rowIds.add(outcome.row().getId());
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(MonitoringException.class);
                conflicts++;
            }
        }
        pool.shutdownNow();
        // exactly ONE owner; same-alert duplicates may replay the SAME row
        // read-only (owner and replays all reference one row id — asserted
        // AFTER polling, replay may resolve first); cross-alert requests MUST
        // conflict (never hand out the other alert's evidence)
        assertThat(owners).isEqualTo(1);
        assertThat(replays + conflicts).isEqualTo(3);
        assertThat(rowIds).as("owner + replays reference exactly one row")
                .hasSize(owners + replays)
                .allMatch(id -> id.equals(rowIds.get(0)));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE idempotency_key = ?",
                Long.class, key);
        assertThat(rows).isEqualTo(1L);
        Long totalSnaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE kind='SNAPSHOT' AND attempt_id = ?",
                Long.class, attemptId);
        assertThat(totalSnaps).isLessThanOrEqualTo(properties.getSnapshotPerAttempt());
    }

    @Test
    void nonJpegPayloadIsRejected() {
        long alertId = openAlertId();
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                "idem-png", base.toEpochMilli(), new byte[]{(byte) 0x89, 'P', 'N', 'G'}))
                .isInstanceOf(MonitoringException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence", Long.class)).isZero();
    }

    @Test
    void foreignStudentAndForeignAlertAreRejected() {
        long alertId = openAlertId();
        assertThatThrownBy(() -> snapshots.upload(otherStudentId, attemptId, alertId,
                "idem-x", base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
        // alert id from a different attempt looks like a plain rejection
        long otherSession = insertOpenSession(jdbc, otherStudentId, base);
        long otherAttempt = attemptService
                .startAttempt(otherStudentId, otherSession, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, otherSession, otherStudentId, otherAttempt, base, null);
        long otherAlert = jdbc.queryForObject(
                "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                        + "alert_type, status, first_detected_at, last_detected_at, detection_count, "
                        + "model_id, model_version, provenance) "
                        + "VALUES (?,?,?,'SCREEN_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, otherAttempt, otherSession, otherStudentId,
                java.sql.Timestamp.from(base.minusSeconds(5)),
                java.sql.Timestamp.from(base.minusSeconds(4)));
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, otherAlert,
                "idem-y", base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class);
    }

    @Test
    void concurrentDuplicateKeySingleWinnerNoOverwrite() throws Exception {
        long alertId = openAlertId();
        String key = "idem-race-" + UUID.randomUUID();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Callable<Object>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> {
                start.await();
                return snapshots.upload(studentId, attemptId, alertId, key,
                        base.toEpochMilli(), REAL_JPEG);
            });
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<Future<Object>> futures = new java.util.ArrayList<>();
        for (java.util.concurrent.Callable<Object> call : calls) {
            futures.add(pool.submit(call));
        }
        start.countDown();
        java.util.Set<UUID> distinctRows = new java.util.HashSet<>();
        int owners = 0;
        for (Future<Object> future : futures) {
            EvidenceSnapshotService.UploadOutcome outcome =
                    (EvidenceSnapshotService.UploadOutcome) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            distinctRows.add(outcome.row().getId());
            if (outcome.owner()) {
                owners++;
            }
            assertThat(outcome.row().getStatus())
                    .as("no half-written state visible to either side")
                    .isIn("AVAILABLE", "PENDING");
        }
        pool.shutdownNow();
        assertThat(distinctRows).as("exactly one row owns the key").hasSize(1);
        assertThat(owners).as("exactly one request finalized").isEqualTo(1);
        // and the surviving row is complete, consistent metadata
        var row = jdbc.queryForMap(
                "SELECT status, sha256, bytes, object_key FROM monitoring_evidence "
                        + "WHERE idempotency_key = ?", key);
        assertThat(row.get("status")).isEqualTo("AVAILABLE");
        assertThat(row.get("sha256")).isEqualTo(EvidenceSnapshotService.sha256Hex(REAL_JPEG));
        assertThat(((Number) row.get("bytes")).longValue()).isEqualTo(REAL_JPEG.length);
    }

    @Test
    void concurrentDistinctKeysCannotExceedPerAlertBudget() throws Exception {
        long alertId = openAlertId();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<Future<Object>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return snapshots.upload(studentId, attemptId, alertId,
                        "idem-budget-" + idx, base.plusSeconds(idx).toEpochMilli(), REAL_JPEG);
            }));
        }
        start.countDown();
        int accepted = 0;
        int rejected = 0;
        for (Future<Object> future : futures) {
            try {
                EvidenceSnapshotService.UploadOutcome outcome =
                        (EvidenceSnapshotService.UploadOutcome) future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome.owner()) {
                    accepted++;
                }
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(MonitoringException.class);
                rejected++;
            }
        }
        pool.shutdownNow();
        assertThat(accepted).as("budget cap 3/alert holds under concurrency").isLessThanOrEqualTo(3);
        assertThat(accepted + rejected).isEqualTo(8);
        Long stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE kind='SNAPSHOT' "
                        + "AND attempt_id = ? AND status = 'AVAILABLE'",
                Long.class, attemptId);
        assertThat(stored).isEqualTo((long) accepted);
    }

    @Test
    void sameKeyDifferentAttemptIsKeyConflict() {
        long alertId = openAlertId();
        String key = "idem-cross-" + UUID.randomUUID();
        snapshots.upload(studentId, attemptId, alertId, key, base.toEpochMilli(), REAL_JPEG);
        // second attempt (fresh session) with the SAME key
        long otherSession = insertOpenSession(jdbc, studentId, base);
        long otherAttempt = attemptService
                .startAttempt(studentId, otherSession, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, otherSession, studentId, otherAttempt, base, null);
        long otherAlert = jdbc.queryForObject(
                "INSERT INTO monitoring_ai_alerts (attempt_id, exam_session_id, user_id, "
                        + "alert_type, status, first_detected_at, last_detected_at, detection_count, "
                        + "model_id, model_version, provenance) "
                        + "VALUES (?,?,?,'SCREEN_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, otherAttempt, otherSession, studentId,
                java.sql.Timestamp.from(base.minusSeconds(5)),
                java.sql.Timestamp.from(base.minusSeconds(4)));
        assertThatThrownBy(() -> snapshots.upload(studentId, otherAttempt, otherAlert,
                key, base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_SNAPSHOT_REJECTED"));
        // original row untouched
        var row = jdbc.queryForMap(
                "SELECT status, object_key FROM monitoring_evidence WHERE idempotency_key = ?", key);
        assertThat(row.get("status")).isEqualTo("AVAILABLE");
    }


    @Test
    void finishedAttemptRejectsSnapshots() {
        long alertId = openAlertId();
        MonitoringCameraFixtures.finishAttempt(jdbc, attemptId, base);
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, alertId,
                "idem-late", base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("EVIDENCE_SNAPSHOT_REJECTED"));
    }

    // === helpers ===

    private AiDetectionBatchRequest batch(AiDetectionBatchRequest.Item item) {
        return new AiDetectionBatchRequest(List.of(item));
    }

    private AiDetectionBatchRequest.Item phoneAt(Instant observedAt, long sequence) {
        return new AiDetectionBatchRequest.Item(UUID.randomUUID(), "PHONE_OBSERVED",
                observedAt, sequence, "yolo26s", "8.4.64-onnx", "ORT_WEB_WASM",
                640, 480, 40, List.of());
    }
}
