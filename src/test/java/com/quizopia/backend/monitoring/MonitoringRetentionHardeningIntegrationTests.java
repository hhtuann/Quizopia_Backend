package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.evidence.application.EvidenceRetentionJob;
import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F10–F13 pre-merge regressions (final-gate follow-up, issue #96):
 *
 * <ul>
 *   <li><b>F10</b> — the approved 90d activity-metadata retention is ENFORCED:
 *       attempt_activity_events + monitoring_alert_reviews swept past the
 *       per-session window (default 90 for legacy sessions); 89d/90d/91d
 *       boundary clock tests; per-session policy override isolation;
 *       answer history / grades / alerts / evidence NEVER touched; idempotent
 *       re-runs.</li>
 *   <li><b>F11</b> — report completeness is honest: &gt;100 evidence / &gt;200
 *       reviews surface totals + truncation flags, and the cursor-paged
 *       drilldown enumerates the full review history.</li>
 *   <li><b>F12</b> — repeated camera lifecycle events produce exact,
 *       non-overlapping, always-closed periods (no dangling open interval).</li>
 *   <li><b>F13</b> — recording gaps never extend beyond the monitoring
 *       window regardless of segment timestamps (shared clamped intervals).</li>
 * </ul>
 *
 * <p>Deliberately NON-transactional (the sweep deletes through its own
 * transaction template); cleanup restores shared-DB isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class MonitoringRetentionHardeningIntegrationTests {

    @Autowired private EvidenceRetentionJob retentionJob;
    @Autowired private AlertReviewService reviewService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private static final Instant NOW = Instant.parse("2026-11-17T16:00:00Z");

    private long teacherId;
    private long sessionId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        clock.setInstant(NOW);
        teacherId = newTeacherUser(jdbc, "retention-t-" + UUID.randomUUID().toString().substring(0, 8));
        long studentId = newUser(jdbc, "retention-s-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, NOW);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", studentId, sessionId, teacherId);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, NOW, null);
    }

    @AfterEach
    void cleanUpCommittedFixture() {
        jdbc.update("DELETE FROM grades WHERE attempt_id IN "
                + "(SELECT id FROM attempts WHERE exam_session_id = ?)", sessionId);
        jdbc.update("DELETE FROM attempts WHERE exam_session_id = ?", sessionId);
        jdbc.update("DELETE FROM exam_sessions WHERE id = ?", sessionId);
    }

    private void insertPolicy(int activityDays) {
        jdbc.update("INSERT INTO session_monitoring_policies "
                + "(exam_session_id, monitoring_enabled, activity_retention_days) "
                + "VALUES (?, true, ?)", sessionId, activityDays);
    }

    private long insertEvent(String type, Instant at) {
        return jdbc.queryForObject(
                "INSERT INTO attempt_activity_events "
                        + "(event_id, attempt_id, event_type, source, server_received_at, metadata) "
                        + "VALUES (?,?,?,'SERVER',?,'{}'::jsonb) RETURNING id",
                Long.class, UUID.randomUUID(), attemptId, type, Timestamp.from(at));
    }

    private long insertAlertAndReview(Instant reviewAt) {
        jdbc.update("INSERT INTO monitoring_ai_alerts "
                + "(attempt_id, exam_session_id, user_id, alert_type, status, "
                + "first_detected_at, last_detected_at, model_id, model_version, provenance) "
                + "VALUES (?,?,?,'PHONE_DETECTED','CLOSED',?,?,"
                + "'yolo26s','8.4.64-onnx','CLIENT_MODEL')",
                attemptId, sessionId,
                jdbc.queryForObject("SELECT user_id FROM attempts WHERE id = ?", Long.class, attemptId),
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(3500)));
        Long alertId = jdbc.queryForObject(
                "SELECT MAX(id) FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class, attemptId);
        jdbc.update("INSERT INTO monitoring_alert_reviews "
                + "(alert_id, reviewer_user_id, action, review_version, idempotency_key, created_at) "
                + "VALUES (?,?, 'NOTED', 1, ?, ?)",
                alertId, teacherId, UUID.randomUUID(), Timestamp.from(reviewAt));
        return alertId;
    }

    // === F10 ===

    @Test
    void sweepDeletesPastPolicyWindowAndKeepsBoundaryRows() {
        insertPolicy(90);
        // 89d kept, exactly-90d kept (strictly-older deleted), 91d deleted
        insertEvent("TAB_HIDDEN", NOW.minus(java.time.Duration.ofDays(89)));
        insertEvent("TAB_VISIBLE", NOW.minus(java.time.Duration.ofDays(90)));
        insertEvent("PAGE_RELOADED", NOW.minus(java.time.Duration.ofDays(91)));
        insertAlertAndReview(NOW.minus(java.time.Duration.ofDays(89)));
        insertAlertAndReview(NOW.minus(java.time.Duration.ofDays(91)));

        retentionJob.sweepActivityMetadata();

        // kept: 89d + exactly-90d + the ATTEMPT_STARTED baseline
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ?",
                Long.class, attemptId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? "
                        + "AND event_type = 'PAGE_RELOADED'", Long.class, attemptId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews r JOIN monitoring_ai_alerts a "
                        + "ON a.id = r.alert_id WHERE a.attempt_id = ?",
                Long.class, attemptId)).isEqualTo(1L);
        // idempotent: a second pass is a no-op
        retentionJob.sweepActivityMetadata();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ?",
                Long.class, attemptId)).isEqualTo(3L);
    }

    @Test
    void perSessionPolicyOverridesAndIsolatesOtherSessions() {
        insertPolicy(30);
        long otherTeacher = newTeacherUser(jdbc, "ret-o-" + UUID.randomUUID().toString().substring(0, 8));
        long otherStudent = newUser(jdbc, "ret-os-" + UUID.randomUUID().toString().substring(0, 8));
        long otherSession = insertOpenSession(jdbc, otherTeacher, NOW);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", otherStudent, otherSession, otherTeacher);
        long otherAttempt = attemptService
                .startAttempt(otherStudent, otherSession, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, otherSession, otherStudent, otherAttempt, NOW, null);
        try {
            insertEvent("TAB_HIDDEN", NOW.minus(java.time.Duration.ofDays(31)));
            jdbc.update("INSERT INTO attempt_activity_events "
                            + "(event_id, attempt_id, event_type, source, server_received_at, metadata) "
                            + "VALUES (?,?,?,'SERVER',?,'{}'::jsonb)",
                    UUID.randomUUID(), otherAttempt, "TAB_HIDDEN",
                    Timestamp.from(NOW.minus(java.time.Duration.ofDays(31))));

            retentionJob.sweepActivityMetadata();

            // 30d session: 31d-old gone (the ATTEMPT_STARTED baseline
            // event stamped at NOW always survives — it is inside the window)
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? "
                            + "AND event_type = 'TAB_HIDDEN'",
                    Long.class, attemptId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? "
                            + "AND event_type = 'ATTEMPT_STARTED'",
                    Long.class, attemptId)).isEqualTo(1L);
            // legacy-default session (no policy row → 90d): the 31d-old
            // TAB_HIDDEN is KEPT (younger than the default window)
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? "
                            + "AND event_type = 'TAB_HIDDEN'",
                    Long.class, otherAttempt)).isEqualTo(1L);
        } finally {
            jdbc.update("DELETE FROM attempts WHERE exam_session_id = ?", otherSession);
            jdbc.update("DELETE FROM exam_sessions WHERE id = ?", otherSession);
        }
    }

    @Test
    void sweepNeverTouchesAnswersGradesAlertsOrEvidence() {
        insertPolicy(30);
        // answer history + grade far past the window — must survive
        jdbc.update("INSERT INTO attempt_answer_history "
                + "(attempt_id, attempt_question_id, accepted_sequence, answer_payload, accepted_at) "
                + "SELECT ?, id, 1, '{}'::jsonb, ? FROM attempt_questions WHERE attempt_id = ? "
                + "ORDER BY id LIMIT 1", attemptId,
                Timestamp.from(NOW.minus(java.time.Duration.ofDays(400))), attemptId);
        jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, "
                + "percentage, status, graded_at) VALUES (?, 1, 1, 4, 25, 'AUTO_GRADED', ?)",
                attemptId, Timestamp.from(NOW.minus(java.time.Duration.ofDays(400))));
        jdbc.update("INSERT INTO monitoring_evidence "
                + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                + "captured_at, bytes, created_at) "
                + "VALUES (?,?,?,?,?,'CLIENT_CAPTURE',?,?,100,?)",
                UUID.randomUUID(), sessionId, attemptId, "SNAPSHOT", "AVAILABLE",
                "evidence/" + UUID.randomUUID(), Timestamp.from(NOW.minus(java.time.Duration.ofDays(400))),
                Timestamp.from(NOW.minus(java.time.Duration.ofDays(400))));
        insertEvent("TAB_HIDDEN", NOW.minus(java.time.Duration.ofDays(31)));
        insertAlertAndReview(NOW.minus(java.time.Duration.ofDays(31)));

        retentionJob.sweepActivityMetadata();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_answer_history WHERE attempt_id = ?",
                Long.class, attemptId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM grades WHERE attempt_id = ?", Long.class, attemptId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_evidence WHERE attempt_id = ?",
                Long.class, attemptId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_ai_alerts WHERE attempt_id = ?",
                Long.class, attemptId)).isEqualTo(1L);
        // only the 31d TAB_HIDDEN is swept; the baseline survives
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id = ? "
                        + "AND event_type = 'TAB_HIDDEN'",
                Long.class, attemptId)).isZero();
    }

    // === F11 ===

    @Test
    void reportSurfacesTotalsAndTruncationWithPagedDrilldown() {
        insertPolicy(90);
        long alertId = insertAlertAndReview(NOW); // one alert
        // 201 reviews: version 1 exists; add 200 more (versions must stay
        // contiguous per alert — use a SECOND alert for volume)
        for (int i = 0; i < 101; i++) {
            jdbc.update("INSERT INTO monitoring_evidence "
                    + "(id, exam_session_id, attempt_id, kind, status, provenance, object_key, "
                    + "captured_at, bytes, created_at) "
                    + "VALUES (?,?,?,?,?,'CLIENT_CAPTURE',?,?,100,?)",
                    UUID.randomUUID(), sessionId, attemptId, "SNAPSHOT", "AVAILABLE",
                    "evidence/" + UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(i + 1)),
                    Timestamp.from(NOW.minusSeconds(i + 1)));
        }
        for (int v = 2; v <= 202; v++) {
            jdbc.update("INSERT INTO monitoring_alert_reviews "
                    + "(alert_id, reviewer_user_id, action, review_version, idempotency_key, "
                    + "created_at) VALUES (?,?, 'NOTED', ?, ?, ?)",
                    alertId, teacherId, v, UUID.randomUUID(),
                    Timestamp.from(NOW.minusSeconds(v)));
        }

        var report = reviewServiceReport();
        assertThat(report.evidenceTotal()).isEqualTo(101L);
        assertThat(report.evidenceTruncated()).isTrue();
        assertThat(report.evidence()).hasSize(100);
        assertThat(report.reviewsTotal()).isEqualTo(202L);
        assertThat(report.reviewsTruncated()).isTrue();
        assertThat(report.reviews()).hasSize(200);

        // drilldown: cursor pages cover ALL 202 rows exactly once
        var page1 = reviewService.attemptReviews(teacherId, attemptId, null, 100);
        assertThat(page1.items()).hasSize(100);
        assertThat(page1.nextCursor()).isNotNull();
        var page2 = reviewService.attemptReviews(teacherId, attemptId, page1.nextCursor(), 100);
        var page3 = reviewService.attemptReviews(teacherId, attemptId, page2.nextCursor(), 100);
        assertThat(page3.nextCursor()).isNull();
        int seen = page1.items().size() + page2.items().size() + page3.items().size();
        assertThat(seen).isEqualTo(202);
        // newest first
        assertThat(page1.items().get(0).reviewId())
                .isGreaterThan(page1.items().get(1).reviewId());
    }

    // === F12 ===

    @Test
    void repeatedConnectedEventsProduceExactClosedPeriods() {
        insertPolicy(90);
        Instant t10 = NOW.minusSeconds(600);
        Instant t12 = NOW.minusSeconds(580);
        Instant t20 = NOW.minusSeconds(560);
        Instant t30 = NOW.minusSeconds(540);
        Instant submitted = NOW.minusSeconds(500);
        // CONNECTED t10, CONNECTED t12 (duplicate), DISCONNECTED t20,
        // DISCONNECTED t22 (duplicate), RECONNECTED t30, submitted at t=500s
        jdbc.update("UPDATE attempts SET started_at = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(700)), attemptId);
        insertEvent("CAMERA_CONNECTED", t10);
        insertEvent("CAMERA_CONNECTED", t12);
        insertEvent("CAMERA_DISCONNECTED", t20);
        insertEvent("CAMERA_DISCONNECTED", NOW.minusSeconds(550));
        insertEvent("CAMERA_RECONNECTED", t30);
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(submitted), "f12-" + attemptId, attemptId);

        var report = reviewServiceReport();
        var connected = report.camera().connectedPeriods();
        var disconnected = report.camera().disconnectPeriods();
        // EXACT: [t10→t20] and [t30→submitted] — no dangling [t10,null],
        // no overlap, duplicates ignored
        assertThat(connected).hasSize(2);
        assertThat(connected.get(0).from()).isEqualTo(t10);
        assertThat(connected.get(0).to()).isEqualTo(t20);
        assertThat(connected.get(1).from()).isEqualTo(t30);
        assertThat(connected.get(1).to()).isEqualTo(submitted);
        assertThat(disconnected).hasSize(1);
        assertThat(disconnected.get(0).from()).isEqualTo(t20);
        assertThat(disconnected.get(0).to()).isEqualTo(t30);
        // nothing left open
        assertThat(connected).allSatisfy(p -> assertThat(p.to()).isNotNull());
    }

    // === F13 ===

    @Test
    void recordingGapsStayInsideTheMonitoringWindow() {
        insertPolicy(90);
        var binding = insertPreflightAndBinding(jdbc, sessionId,
                jdbc.queryForObject("SELECT user_id FROM attempts WHERE id = ?", Long.class, attemptId),
                attemptId, NOW, null);
        Instant windowStart = NOW.minusSeconds(600);
        Instant windowEnd = NOW.minusSeconds(300);
        // one segment ENTIRELY BEFORE the window, one INSIDE, one AFTER —
        // gaps must stay clamped to [windowStart, windowEnd]
        insertJob(binding.bindingId(), "COMPLETE",
                NOW.minusSeconds(800), NOW.minusSeconds(700)); // before
        insertJob(binding.bindingId(), "COMPLETE",
                NOW.minusSeconds(500), NOW.minusSeconds(400)); // inside
        insertJob(binding.bindingId(), "COMPLETE",
                NOW.minusSeconds(200), NOW.minusSeconds(100)); // after
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                        + "submission_idempotency_key=? WHERE id=?",
                Timestamp.from(windowEnd), "f13-" + attemptId, attemptId);
        jdbc.update("UPDATE attempts SET started_at = ? WHERE id = ?",
                Timestamp.from(windowStart), attemptId);

        var report = reviewServiceReport();
        for (var gap : report.recording().gaps()) {
            assertThat(gap.from()).isAfterOrEqualTo(windowStart);
            assertThat(gap.to()).isBeforeOrEqualTo(windowEnd);
        }
        // window = 300s, covered (clamped) = 100s inside → exact ratio
        assertThat(report.recording().coverageRatio()).isEqualTo(0.33); // 100/300, 2dp rounding
    }

    private void insertJob(UUID bindingId, String status, Instant started, Instant ended) {
        jdbc.update("INSERT INTO monitoring_recording_jobs "
                + "(id, exam_session_id, attempt_id, binding_id, kind, egress_id, room_name, "
                + "track_sid, status, started_at, ended_at, object_key) "
                + "VALUES (?,?,?,?, 'ROLLING', ?, 'room', 'track', ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, attemptId, bindingId, "eg-" + UUID.randomUUID(),
                status, Timestamp.from(started), Timestamp.from(ended),
                "recordings/" + UUID.randomUUID());
    }

    private com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse reviewServiceReport() {
        // report via the report service (same gate)
        return reportService.report(teacherId, attemptId);
    }

    @Autowired private com.quizopia.backend.monitoring.application.MonitoringReportService reportService;
}
