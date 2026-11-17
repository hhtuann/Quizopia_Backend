package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenCameraConnection;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.monitoring.application.MonitoringDashboardService;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringOverviewResponse;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringParticipantsResponse;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * P4-E dashboard read tests (issue #95, ADR-010 §2.3): overview counters,
 * participants signal separation (camera/AI/recording/evidence), cursor
 * determinism, filters and IDOR.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class MonitoringDashboardIntegrationTests {

    @Autowired private MonitoringDashboardService dashboard;
    @Autowired private AlertReviewService reviewService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long teacherId;
    private long otherTeacherId;
    private long sessionId;
    private long connectedStudentId;
    private long idleStudentId;
    private long noBindingStudentId;
    private long connectedAttempt;
    private long idleAttempt;
    private long noBindingAttempt;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-17T10:00:00Z");
        clock.setInstant(base.plusSeconds(600));
        teacherId = newTeacherUser(jdbc, "dashteacher" + "-" + UUID.randomUUID().toString().substring(0, 8));
        otherTeacherId = newTeacherUser(jdbc, "dashother" + "-" + UUID.randomUUID().toString().substring(0, 8));
        connectedStudentId = newUser(jdbc, "dashconnected" + "-" + UUID.randomUUID().toString().substring(0, 8));
        idleStudentId = newUser(jdbc, "dashidle" + "-" + UUID.randomUUID().toString().substring(0, 8));
        noBindingStudentId = newUser(jdbc, "dashnobinding" + "-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, base);
        for (long sid : new long[] {connectedStudentId, idleStudentId, noBindingStudentId}) {
            jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                    + "VALUES (?,?,?)", sid, sessionId, teacherId);
        }
        // Start BEFORE any policy exists — the monitored start gate would
        // otherwise demand a readiness lease (fixture convention).
        connectedAttempt = start(connectedStudentId);
        idleAttempt = start(idleStudentId);
        noBindingAttempt = start(noBindingStudentId);
        enableMonitoringWithPolicy(jdbc, sessionId, true, false, true);
        // CONNECTED: binding + open camera connection
        var connected = insertPreflightAndBinding(jdbc, sessionId, connectedStudentId,
                connectedAttempt, base, null);
        insertOpenCameraConnection(jdbc, connected.bindingId(), "track-1", base);
        // IDLE: binding, never connected, attempt IN_PROGRESS is NOT enough for
        // RECONNECTING because no camera was EVER verified for this binding…
        // actually IN_PROGRESS + active binding reads RECONNECTING (the P4-B
        // convention: camera verified at preflight, waiting to re-see it).
        // To force IDLE we submit the attempt.
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(base.plusSeconds(600)), "dash-" + idleAttempt, idleAttempt);
        insertPreflightAndBinding(jdbc, sessionId, idleStudentId, idleAttempt, base, null);
    }

    private long start(long studentId) {
        return attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
    }

    private void raiseAlert(long attemptId, long userId, String type, String detectionType) {
        jdbc.update("INSERT INTO monitoring_ai_detections "
                + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                + "model_id, model_version, runtime, observed_at, quarantined, payload, "
                + "frame_width, frame_height, processing_ms, client_sequence) "
                + "VALUES (?,?,?,?,'CLIENT_MODEL',?,'yolo26s','8.4.64-onnx','ORT_WEB_WASM',"
                + "?,false,'[]'::jsonb,640,480,40,1)",
                UUID.randomUUID(), attemptId, sessionId, userId, detectionType,
                Timestamp.from(base.plusSeconds(30)));
        jdbc.update("INSERT INTO monitoring_ai_alerts "
                + "(attempt_id, exam_session_id, user_id, alert_type, status, "
                + "first_detected_at, last_detected_at, detection_count, model_id, model_version, "
                + "provenance) VALUES (?,?,?,?,'OPEN',?,?,1,'yolo26s','8.4.64-onnx','CLIENT_MODEL')",
                attemptId, sessionId, userId, type,
                Timestamp.from(base.plusSeconds(30)), Timestamp.from(base.plusSeconds(30)));
    }

    private void insertEvidence(long attemptId, String kind, String status) {
        boolean snapshot = "SNAPSHOT".equals(kind);
        jdbc.update("INSERT INTO monitoring_evidence "
                + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                + "captured_at, window_start, window_end, bytes) "
                + "VALUES (?,?,?,?,?,'CLIENT_CAPTURE',?,?,?,?,100)",
                UUID.randomUUID(), sessionId, attemptId, kind, status,
                "evidence/" + UUID.randomUUID(),
                snapshot ? Timestamp.from(base) : null,
                snapshot ? null : Timestamp.from(base),
                snapshot ? null : Timestamp.from(base.plusSeconds(5)));
    }

    private void insertJob(long attemptId, String status) {
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                + "track_sid, status, started_at, ended_at) "
                + "SELECT ?, ?, ?, b.id, 'ROLLING', ?, 'room', b.media_identity, ?, ?, ? "
                + "FROM monitoring_media_bindings b WHERE b.attempt_id = ? "
                + "ORDER BY b.id DESC LIMIT 1",
                UUID.randomUUID(), sessionId, attemptId, "eg-" + UUID.randomUUID(),
                status, Timestamp.from(base), Timestamp.from(base.plusSeconds(30)), attemptId);
    }

    // === overview ===

    @Test
    void overviewCountsEverySignalSeparately() {
        raiseAlert(connectedAttempt, connectedStudentId, "PHONE_DETECTED", "PHONE_OBSERVED");
        raiseAlert(idleAttempt, idleStudentId, "SCREEN_DETECTED", "SCREEN_OBSERVED");
        // review the second alert → unreviewed drops to 1
        long reviewedAlert = jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class, idleAttempt);
        reviewService.review(teacherId, reviewedAlert, 0, "MARK_VIEWED", null, UUID.randomUUID());

        insertEvidence(connectedAttempt, "SNAPSHOT", "AVAILABLE");
        insertEvidence(idleAttempt, "SNAPSHOT", "FAILED");
        insertJob(connectedAttempt, "COMPLETE");
        insertJob(connectedAttempt, "ACTIVE");

        MonitoringOverviewResponse overview = dashboard.overview(teacherId, sessionId);

        assertThat(overview.monitoringEnabled()).isTrue();
        assertThat(overview.attempts().total()).isEqualTo(3);
        assertThat(overview.attempts().inProgress()).isEqualTo(2);
        assertThat(overview.attempts().submitted()).isEqualTo(1);
        assertThat(overview.attempts().graded()).isZero();
        assertThat(overview.camera().connected()).isEqualTo(1);
        assertThat(overview.camera().idle()).isEqualTo(1);
        assertThat(overview.camera().reconnecting()).isZero();
        assertThat(overview.ai().openAlerts()).isEqualTo(2);
        assertThat(overview.ai().unreviewedAlerts()).isEqualTo(1);
        assertThat(overview.recording().rollingBufferActive()).isTrue();
        assertThat(overview.recording().activeJobs()).isEqualTo(1);
        assertThat(overview.recording().completeJobs()).isEqualTo(1);
        assertThat(overview.evidence().available()).isEqualTo(1);
        assertThat(overview.evidence().failed()).isEqualTo(1);
        assertThat(overview.asOf()).isNotNull();
    }

    // === participants: signal separation ===

    @Test
    void participantsSeparateCameraAiRecordingEvidenceSignals() {
        raiseAlert(connectedAttempt, connectedStudentId, "PHONE_DETECTED", "PHONE_OBSERVED");
        insertEvidence(connectedAttempt, "SNAPSHOT", "AVAILABLE");
        insertJob(connectedAttempt, "COMPLETE");

        MonitoringParticipantsResponse response =
                dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        assertThat(response.items()).hasSize(3);

        var connected = response.items().stream()
                .filter(i -> i.attemptId().equals(connectedAttempt)).findFirst().orElseThrow();
        assertThat(connected.camera().state()).isEqualTo("CONNECTED");
        assertThat(connected.camera().connectedSince()).isNotNull();
        assertThat(connected.attemptStatus()).isEqualTo("IN_PROGRESS");
        assertThat(connected.ai().totalAlerts()).isEqualTo(1);
        assertThat(connected.ai().unreviewedAlerts()).isEqualTo(1);
        assertThat(connected.reviewStatus()).isEqualTo("UNREVIEWED");
        assertThat(connected.recording().mode()).isEqualTo("ROLLING");
        assertThat(connected.recording().completeJobs()).isEqualTo(1);
        assertThat(connected.evidence().snapshots()).isEqualTo(1);
        assertThat(connected.displayName()).startsWith("DASHCONNECTED");

        var idle = response.items().stream()
                .filter(i -> i.attemptId().equals(idleAttempt)).findFirst().orElseThrow();
        // Binding exists but no open connection and attempt no longer
        // IN_PROGRESS → IDLE (not NONE: the student DID bind a camera).
        assertThat(idle.camera().state()).isEqualTo("IDLE");
        assertThat(idle.attemptStatus()).isEqualTo("SUBMITTED");

        var none = response.items().stream()
                .filter(i -> i.attemptId().equals(noBindingAttempt)).findFirst().orElseThrow();
        // Honest absence — never an inferred verdict.
        assertThat(none.camera().state()).isEqualTo("NONE");
        assertThat(none.camera().mediaIdentity()).isNull();
        assertThat(none.ai().totalAlerts()).isZero();
        assertThat(none.reviewStatus()).isEqualTo("UNREVIEWED");
    }

    // === cursor + filters ===

    @Test
    void participantsCursorIsDeterministicNewestAttemptFirst() {
        var first = dashboard.participants(teacherId, sessionId, null, 2, null, null, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        var second = dashboard.participants(teacherId, sessionId, first.nextCursor(),
                2, null, null, null);
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        // DESC ordering
        assertThat(first.items().get(0).attemptId())
                .isGreaterThan(first.items().get(1).attemptId());
        assertThat(second.items().get(0).attemptId())
                .isLessThan(first.items().get(1).attemptId());
        // invalid cursor
        assertThatThrownBy(() -> dashboard.participants(teacherId, sessionId, "bogus",
                2, null, null, null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_CURSOR_INVALID));
    }

    @Test
    void participantsFiltersByCameraStateAndReviewStatus() {
        raiseAlert(connectedAttempt, connectedStudentId, "PHONE_DETECTED", "PHONE_OBSERVED");
        var connectedOnly = dashboard.participants(teacherId, sessionId, null, 50,
                null, "CONNECTED", null);
        assertThat(connectedOnly.items()).hasSize(1);
        assertThat(connectedOnly.items().get(0).attemptId()).isEqualTo(connectedAttempt);

        var noneOnly = dashboard.participants(teacherId, sessionId, null, 50,
                null, "NONE", null);
        assertThat(noneOnly.items()).hasSize(1);
        assertThat(noneOnly.items().get(0).attemptId()).isEqualTo(noBindingAttempt);

        // review the only alert → aggregate reviewStatus leaves UNREVIEWED
        long alertId = jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class,
                connectedAttempt);
        reviewService.review(teacherId, alertId, 0, "FALSE_POSITIVE", null, UUID.randomUUID());
        var reviewedOnly = dashboard.participants(teacherId, sessionId, null, 50,
                null, null, "REVIEWED");
        assertThat(reviewedOnly.items()).hasSize(1);
        assertThat(reviewedOnly.items().get(0).attemptId()).isEqualTo(connectedAttempt);
        // single-state aggregate surfaces the exact state (per-state filter)
        var falsePositiveOnly = dashboard.participants(teacherId, sessionId, null, 50,
                null, null, "FALSE_POSITIVE");
        assertThat(falsePositiveOnly.items()).hasSize(1);
        assertThat(falsePositiveOnly.items().get(0).attemptId()).isEqualTo(connectedAttempt);
        // a state nobody holds filters to nothing
        var viewedOnly = dashboard.participants(teacherId, sessionId, null, 50,
                null, null, "VIEWED");
        assertThat(viewedOnly.items()).isEmpty();

        assertThatThrownBy(() -> dashboard.participants(teacherId, sessionId, null, 50,
                null, "FLYING", null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_VALIDATION_ERROR));
    }

    // === IDOR ===

    @Test
    void overviewAndParticipantsDenyForeignTeacherStudentAndUnknownSession() {
        for (long intruder : new long[] {otherTeacherId, connectedStudentId}) {
            assertThatThrownBy(() -> dashboard.overview(intruder, sessionId))
                    .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                            .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
            assertThatThrownBy(() -> dashboard.participants(intruder, sessionId, null,
                    50, null, null, null))
                    .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                            .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        }
        assertThatThrownBy(() -> dashboard.overview(teacherId, 999999L))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }
}
