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
import com.quizopia.backend.evidence.application.EvidenceSnapshotService.SnapshotResult;
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
        SnapshotResult result = snapshots.upload(studentId, attemptId, alertId,
                "idem-" + UUID.randomUUID(), base.toEpochMilli(), REAL_JPEG);

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.provenance()).isEqualTo("CLIENT_CAPTURE");
        var row = jdbc.queryForMap(
                "SELECT object_key, sha256, bytes, provenance, status FROM monitoring_evidence "
                        + "WHERE id = ?", java.util.UUID.fromString(result.evidenceId().toString()));
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
        SnapshotResult first = snapshots.upload(studentId, attemptId, alertId,
                key, base.toEpochMilli(), REAL_JPEG);
        SnapshotResult retry = snapshots.upload(studentId, attemptId, alertId,
                key, base.plusSeconds(2).toEpochMilli(), REAL_JPEG);
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.evidenceId()).isEqualTo(first.evidenceId());
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
                        + "VALUES (?,?,?,'PHONE_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                        + "'CLIENT_MODEL') RETURNING id",
                Long.class, otherAttempt, otherSession, otherStudentId,
                java.sql.Timestamp.from(base.minusSeconds(5)),
                java.sql.Timestamp.from(base.minusSeconds(4)));
        assertThatThrownBy(() -> snapshots.upload(studentId, attemptId, otherAlert,
                "idem-y", base.toEpochMilli(), REAL_JPEG))
                .isInstanceOf(MonitoringException.class);
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
