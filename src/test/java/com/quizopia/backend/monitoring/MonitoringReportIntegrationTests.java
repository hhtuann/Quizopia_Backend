package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.monitoring.application.MonitoringReportService;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.sql.Timestamp;
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
 * P4-E attempt monitoring report tests (issue #96, ADR-010 §2.4): three-layer
 * count separation, camera/recording periods, evidence honesty, grade
 * isolation and post-close availability.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class MonitoringReportIntegrationTests {

    @Autowired private MonitoringReportService reportService;
    @Autowired private AlertReviewService reviewService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private Instant base;
    private long teacherId;
    private long otherTeacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private long alertId;
    private UUID bindingId;

    /**
     * These suites are deliberately NON-transactional (REQUIRES_NEW append /
     * concurrency semantics need committed data). This restores the shared
     * container DB isolation every other suite gets from rollback: deleting
     * the session cascades exam graph → attempts → alerts/reviews/evidence/
     * jobs (all FKs ON DELETE CASCADE).
     */
    @org.junit.jupiter.api.AfterEach
    void cleanUpCommittedFixture() {
        // fk_attempts_session is ON DELETE RESTRICT (V24) — attempts (and
        // their cascading monitoring children) go first, then the session.
        jdbc.update("DELETE FROM grades WHERE attempt_id IN "
                + "(SELECT id FROM attempts WHERE exam_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM attempts WHERE exam_session_id = ?", sessionId);
        jdbc.update("DELETE FROM exam_sessions WHERE id = ?", sessionId);
    }

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-17T11:00:00Z");
        clock.setInstant(base.plusSeconds(600));
        teacherId = newTeacherUser(jdbc, "repteacher" + "-" + UUID.randomUUID().toString().substring(0, 8));
        otherTeacherId = newTeacherUser(jdbc, "repother" + "-" + UUID.randomUUID().toString().substring(0, 8));
        studentId = newUser(jdbc, "repstudent" + "-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, base);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", studentId, sessionId, teacherId);
        // Start BEFORE any policy exists — the monitored start gate would
        // otherwise demand a readiness lease (fixture convention).
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        enableMonitoringWithPolicy(jdbc, sessionId, true, true, true);
        var binding = insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId,
                base, null);
        bindingId = binding.bindingId();
    }

    private void activity(String type, Instant at) {
        jdbc.update("INSERT INTO attempt_activity_events "
                + "(event_id, attempt_id, event_type, source, server_received_at, metadata) "
                + "VALUES (?,?,?,'SERVER',?,'{}'::jsonb)",
                UUID.randomUUID(), attemptId, type, Timestamp.from(at));
    }

    private void insertDetections(int count, String detectionType) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO monitoring_ai_detections "
                    + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                    + "model_id, model_version, runtime, observed_at, quarantined, payload, "
                    + "frame_width, frame_height, processing_ms, client_sequence) "
                    + "VALUES (?,?,?,?,'CLIENT_MODEL',?,'yolo26s','8.4.64-onnx','ORT_WEB_WASM',"
                    + "?,false,'[]'::jsonb,640,480,40,?)",
                    UUID.randomUUID(), attemptId, sessionId, studentId, detectionType,
                    Timestamp.from(base.plusSeconds(100 + i)), i + 1);
        }
    }

    private void insertEvidence(String kind, String status, boolean expired) {
        boolean snapshot = "SNAPSHOT".equals(kind);
        jdbc.update("INSERT INTO monitoring_evidence "
                + "(id, exam_session_id, attempt_id, alert_id, kind, status, provenance, "
                + "object_key, captured_at, window_start, window_end, bytes, expires_at) "
                + "VALUES (?,?,?,?,?,?,'CLIENT_CAPTURE',?,?,?,?,100,?)",
                UUID.randomUUID(), sessionId, attemptId, alertId, kind, status,
                "evidence/" + UUID.randomUUID(),
                snapshot ? Timestamp.from(base.plusSeconds(200)) : null,
                snapshot ? null : Timestamp.from(base.plusSeconds(200)),
                snapshot ? null : Timestamp.from(base.plusSeconds(205)),
                Timestamp.from(expired ? base.minusSeconds(1) : base.plusSeconds(86400)));
    }

    private void insertJob(String status, Instant startedAt, Instant endedAt) {
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                + "track_sid, status, started_at, ended_at, object_key) "
                + "VALUES (?,?,?,?, 'ROLLING', ?, 'room', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, attemptId, bindingId, "eg-" + UUID.randomUUID(),
                "track", status, Timestamp.from(startedAt), Timestamp.from(endedAt),
                "recordings/" + UUID.randomUUID());
    }

    /** Full fixture: camera lifecycle, detections/alerts/reviews, evidence, segments. */
    private void seedFullAttempt() {
        activity("ANSWER_ACCEPTED", base.plusSeconds(10));
        activity("ANSWER_ACCEPTED", base.plusSeconds(20));
        activity("TAB_HIDDEN", base.plusSeconds(30));
        activity("TAB_VISIBLE", base.plusSeconds(40));
        activity("WINDOW_FOCUS_LOST", base.plusSeconds(50));
        activity("PAGE_RELOADED", base.plusSeconds(60));
        jdbc.update("INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) "
                + "SELECT ?, id, 1, '{}'::jsonb, ? FROM attempt_questions WHERE attempt_id = ? "
                + "ORDER BY id LIMIT 1", attemptId, Timestamp.from(base.plusSeconds(10)), attemptId);

        // camera lifecycle AFTER the attempt's real start (setUp clock is
        // base+600 — periods are clamped to [startedAt, windowEnd] per F02)
        activity("CAMERA_CONNECTED", base.plusSeconds(610));
        activity("CAMERA_DISCONNECTED", base.plusSeconds(620));
        activity("CAMERA_RECONNECTED", base.plusSeconds(630));
        activity("MONITORING_GAP", base.plusSeconds(635));

        // AI: 3 raw detections, 1 quarantined, ONE grouped window
        insertDetections(3, "PHONE_OBSERVED");
        jdbc.update("INSERT INTO monitoring_ai_detections "
                + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                + "model_id, model_version, runtime, observed_at, quarantined, "
                + "quarantine_reason, payload, frame_width, frame_height, processing_ms, "
                + "client_sequence) VALUES (?,?,?,?,'CLIENT_MODEL','PHONE_OBSERVED','yolo26s',"
                + "'8.4.64-onnx','ORT_WEB_WASM',?,true,'LATE','[]'::jsonb,640,480,40,99)",
                UUID.randomUUID(), attemptId, sessionId, studentId,
                Timestamp.from(base.plusSeconds(101)));
        jdbc.update("INSERT INTO monitoring_ai_alerts "
                + "(attempt_id, exam_session_id, user_id, alert_type, status, first_detected_at, "
                + "last_detected_at, detection_count, max_confidence, model_id, model_version, "
                + "provenance) VALUES (?,?,?,'PHONE_DETECTED','OPEN',?,?,3,0.9,'yolo26s',"
                + "'8.4.64-onnx','CLIENT_MODEL')",
                attemptId, sessionId, studentId, Timestamp.from(base.plusSeconds(100)),
                Timestamp.from(base.plusSeconds(102)));
        alertId = jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class, attemptId);
        reviewService.review(teacherId, alertId, 0, "FALSE_POSITIVE", "artefact",
                UUID.randomUUID());

        insertEvidence("SNAPSHOT", "AVAILABLE", false);
        insertEvidence("SNAPSHOT", "EXPIRED", true);
        insertEvidence("CLIP", "FAILED", false);

        // two COMPLETE segments INSIDE the monitoring window (600→800) with
        // a 10s hole between them; FAILED excluded everywhere (F06: the gap
        // list covers leading + between + trailing holes of the window).
        insertJob("COMPLETE", base.plusSeconds(650), base.plusSeconds(680));
        insertJob("COMPLETE", base.plusSeconds(690), base.plusSeconds(720));
        insertJob("FAILED", base.plusSeconds(730), base.plusSeconds(740));

        // close the attempt + session — the report must survive both
        // (submitted_at AFTER every fixture event and the attempt's real
        // start under the advanced clock)
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(base.plusSeconds(800)), "rep-" + attemptId, attemptId);
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=? WHERE id=?",
                Timestamp.from(base.plusSeconds(250)), sessionId);
        clock.advance(java.time.Duration.ofSeconds(300));
    }

    @Test
    void reportSeparatesDetectionAlertReviewLayersWithoutDoubleCounting() {
        seedFullAttempt();
        AttemptMonitoringReportResponse report = reportService.report(teacherId, attemptId);

        // three layers, exactly one alert, exactly one review
        assertThat(report.ai().rawDetections()).isEqualTo(3);
        assertThat(report.ai().quarantined()).isEqualTo(1);
        assertThat(report.ai().groupedAlerts()).isEqualTo(1);
        assertThat(report.ai().reviewedAlerts()).isEqualTo(1);
        assertThat(report.ai().falsePositives()).isEqualTo(1);
        assertThat(report.ai().models()).hasSize(1);
        assertThat(report.ai().models().get(0).detections()).isEqualTo(3);

        assertThat(report.activity().answerAccepted()).isEqualTo(2);
        assertThat(report.activity().distinctQuestionsAnswered()).isEqualTo(1);
        assertThat(report.activity().browser().tabHidden()).isEqualTo(1);
        assertThat(report.activity().browser().focusLost()).isEqualTo(1);
        assertThat(report.activity().browser().pageReload()).isEqualTo(1);

        // camera: MONITORING_GAP counts as a coverage break — connected
        // [10→20] and [30→35], disconnected [20→30] and [35→report-now]
        assertThat(report.camera().neverConnected()).isFalse();
        assertThat(report.camera().connectedPeriods()).hasSize(2);
        assertThat(report.camera().connectedPeriods().get(0).from())
                .isEqualTo(base.plusSeconds(610));
        assertThat(report.camera().disconnectPeriods()).hasSize(2);
        assertThat(report.camera().monitoringGaps()).isEqualTo(1);
        assertThat(report.camera().lastEvent()).isEqualTo("CAMERA_RECONNECTED");

        // recording: FAILED excluded; gaps = leading 50s + between 10s +
        // trailing 80s (window 600→800, coverage ends at 720) — F06
        // semantics relative to the MONITORING WINDOW, exact values.
        assertThat(report.recording().segments()).hasSize(3);
        assertThat(report.recording().gaps()).hasSize(3);
        assertThat(report.recording().gaps().stream()
                .filter(g -> g.seconds() == 10.0).count()).isEqualTo(1);
        assertThat(report.recording().gaps().stream()
                .filter(g -> g.seconds() == 50.0).count()).isEqualTo(1);
        assertThat(report.recording().gaps().stream()
                .filter(g -> g.seconds() == 80.0).count()).isEqualTo(1);
        // covered union = 30+30s of a 200s window — exact, not just [0,1]
        assertThat(report.recording().coverageRatio()).isEqualTo(0.3);

        assertThat(report.reviews()).hasSize(1);
        assertThat(report.reviews().get(0).note()).isEqualTo("artefact");

        assertThat(report.attempt().attemptStatus()).isEqualTo("SUBMITTED");
        assertThat(report.monitoringWindow().minutes()).isPositive();
    }

    @Test
    void evidenceHonestyExpiredHasNoPlaybackRef() {
        seedFullAttempt();
        AttemptMonitoringReportResponse report = reportService.report(teacherId, attemptId);
        var byStatus = report.evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AttemptMonitoringReportResponse.EvidenceRow::status, e -> e));
        assertThat(byStatus.get("AVAILABLE").playbackRef())
                .startsWith("/api/exam-sessions/" + sessionId + "/evidence/");
        assertThat(byStatus.get("EXPIRED").playbackRef()).isNull();
        assertThat(byStatus.get("EXPIRED").expiresAt()).isBefore(Instant.now(clock));
        assertThat(byStatus.get("FAILED").playbackRef()).isNull();
        // FAILED clip keeps honest window metadata
        assertThat(byStatus.get("FAILED").windowStart()).isNotNull();
    }

    @Test
    void reportNeverLeaksGradesOrCorrectness() throws Exception {
        seedFullAttempt();
        // a grade exists — the report must not expose any of it
        jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, "
                + "percentage, status, graded_at) VALUES (?, 1, 1, 4, 25, 'AUTO_GRADED', ?)",
                attemptId, Timestamp.from(base.plusSeconds(210)));
        AttemptMonitoringReportResponse report = reportService.report(teacherId, attemptId);
        String json = objectMapper.writeValueAsString(report).toLowerCase();
        // ("verdict" appears ONLY inside the fixed disclaimer — checked via
        // the DTO below; no grading vocabulary anywhere else)
        assertThat(json).doesNotContain("grade").doesNotContain("score")
                .doesNotContain("iscorrect")
                .contains("no automated misconduct verdict");
        assertThat(report.disclaimer())
                .isEqualTo(MonitoringReportService.DISCLAIMER);
    }

    @Test
    void reportDeniesForeignTeacherStudentAndUnknownAttempt() {
        seedFullAttempt();
        for (long intruder : new long[] {otherTeacherId, studentId}) {
            assertThatThrownBy(() -> reportService.report(intruder, attemptId))
                    .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                            .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        }
        assertThatThrownBy(() -> reportService.report(teacherId, 999999L))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
    }

    @Test
    void cameraPeriodsNeverExtendBeyondSubmittedAttemptF02() {
        // Attempt still IN_PROGRESS with an open connected period; submitted
        // 24h BEFORE the report is read — the trailing period must end at
        // submittedAt, never at the report's now (F02).
        activity("CAMERA_CONNECTED", base.plusSeconds(610));
        Instant submitted = base.plusSeconds(700);
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(submitted), "f02-" + attemptId, attemptId);
        clock.advance(java.time.Duration.ofHours(24));

        var report = reportService.report(teacherId, attemptId);
        assertThat(report.camera().connectedPeriods()).hasSize(1);
        var period = report.camera().connectedPeriods().get(0);
        assertThat(period.from()).isEqualTo(base.plusSeconds(610));
        assertThat(period.to()).as("bounded by submittedAt, not report-now")
                .isEqualTo(submitted);
        // Coverage relative to the attempt window, not the 24h-since clock.
        assertThat(report.monitoringWindow().to()).isEqualTo(submitted);
    }

    @Test
    void emptyAttemptReportIsWellFormed() {
        AttemptMonitoringReportResponse report = reportService.report(teacherId, attemptId);
        assertThat(report.camera().neverConnected()).isTrue();
        assertThat(report.camera().connectedPeriods()).isEmpty();
        assertThat(report.ai().rawDetections()).isZero();
        assertThat(report.ai().models()).isEmpty();
        assertThat(report.recording().segments()).isEmpty();
        assertThat(report.recording().coverageRatio()).isZero();
        assertThat(report.evidence()).isEmpty();
        assertThat(report.reviews()).isEmpty();
    }
}
