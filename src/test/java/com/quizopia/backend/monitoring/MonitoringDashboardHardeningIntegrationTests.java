package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenCameraConnection;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.MonitoringDashboardService;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringOverviewResponse;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringParticipantsResponse;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-merge hardening regressions (issues #95/#96 audit):
 *
 * <ul>
 *   <li><b>F03</b> — camera/review filters run INSIDE the keyset query: a
 *       filtered page can never be a false "no participants" while matches
 *       exist on later pages; matches enumerate exactly once.</li>
 *   <li><b>F04</b> — signal separation: transport online vs publishing
 *       (verified track) vs revoked/idle/none; AI enabled/detections/
 *       quarantined distinct from alert rollups; teacher subscription is
 *       honestly NOT_AVAILABLE.</li>
 *   <li><b>F07</b> — overview + participants resolve the user's LATEST
 *       binding only: a historical REVOKED binding never overrides a newer
 *       CONNECTED one.</li>
 *   <li><b>audit</b> — legacy sessions without a policy row never 500.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class MonitoringDashboardHardeningIntegrationTests {

    @Autowired private MonitoringDashboardService dashboard;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long teacherId;
    private long sessionId;

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
        base = Instant.parse("2026-11-17T15:00:00Z");
        clock.setInstant(base);
        teacherId = newTeacherUser(jdbc, "hardteacher");
        sessionId = insertOpenSession(jdbc, teacherId, base);
    }

    private long startFor(long studentId) {
        return attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
    }

    // === F03: filtered pagination is complete ===

    @Test
    void cameraFilterFindsMatchesBeyondTheFirstPageExactlyOnce() {
        // 12 attempts; SEVEN users connect a camera (matches span pages of
        // 5: pages 1-2 carry 5+2 matches — the OLDEST ids live on the last
        // page, exactly where the historical post-filter bug lost them).
        List<Long> connectedAttempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long student = newUser(jdbc, "hard-f03-" + i);
            jdbc.update("INSERT INTO exam_session_participants "
                    + "(user_id, exam_session_id, added_by) VALUES (?,?,?)",
                    student, sessionId, teacherId);
            long attempt = startFor(student);
            if (i >= 5) {
                var binding = insertPreflightAndBinding(jdbc, sessionId, student, attempt,
                        base.plusSeconds(i), null);
                insertOpenCameraConnection(jdbc, binding.bindingId(), "track-" + i,
                        base.plusSeconds(i));
                connectedAttempts.add(attempt);
            }
        }
        assertThat(connectedAttempts).hasSize(7);

        // Pages of 5 with cameraState=CONNECTED — the three matches live at
        // the OLDEST attempt ids (last keyset page). The historical bug
        // post-filtered AFTER paging and returned an empty first page.
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            MonitoringParticipantsResponse page = dashboard.participants(teacherId, sessionId,
                    cursor, 5, null, "CONNECTED", null);
            page.items().forEach(item -> assertThat(seen.add(item.attemptId()))
                    .as("no attempt appears twice across pages").isTrue());
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertThat(pages).as("pagination must terminate").isLessThan(10);
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(connectedAttempts);
        assertThat(pages).isGreaterThanOrEqualTo(2); // really crossed pages
    }

    @Test
    void reviewStatusFilterPagesCompletely() {
        // 7 attempts, the two OLDEST get an alert that is then reviewed.
        List<Long> reviewed = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            long student = newUser(jdbc, "hard-f03r-" + i);
            jdbc.update("INSERT INTO exam_session_participants "
                    + "(user_id, exam_session_id, added_by) VALUES (?,?,?)",
                    student, sessionId, teacherId);
            long attempt = startFor(student);
            if (i < 2) {
                jdbc.update("INSERT INTO monitoring_ai_alerts "
                        + "(attempt_id, exam_session_id, user_id, alert_type, status, "
                        + "first_detected_at, last_detected_at, model_id, model_version, "
                        + "provenance, review_status, review_version) "
                        + "VALUES (?,?,?,'PHONE_DETECTED','OPEN',?,?,"
                        + "'yolo26s','8.4.64-onnx','CLIENT_MODEL','FALSE_POSITIVE',1)",
                        attempt, sessionId, student,
                        java.sql.Timestamp.from(base), java.sql.Timestamp.from(base));
                reviewed.add(attempt);
            }
        }
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        while (true) {
            var page = dashboard.participants(teacherId, sessionId, cursor, 3,
                    null, null, "FALSE_POSITIVE");
            page.items().forEach(i -> assertThat(seen.add(i.attemptId())).isTrue());
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(reviewed);
    }

    // === F07: latest binding wins ===

    @Test
    void historicalRevokedBindingNeverOverridesNewestConnectedBinding() {
        long student = newUser(jdbc, "hard-f07");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", student, sessionId, teacherId);
        long attempt = startFor(student);

        // OLD binding: bound to an earlier attempt, camera closed, REVOKED.
        long firstAttempt = attempt;
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, "
                        + "submission_idempotency_key=? WHERE id=?",
                java.sql.Timestamp.from(base.minusSeconds(60)), "f07a-" + firstAttempt,
                firstAttempt);
        var old = insertPreflightAndBinding(jdbc, sessionId, student, firstAttempt,
                base.minusSeconds(3600), null);
        jdbc.update("UPDATE monitoring_media_bindings SET revoked_at = ?, active_from = ? "
                        + "WHERE id = ?",
                java.sql.Timestamp.from(base.minusSeconds(30)),
                java.sql.Timestamp.from(base.minusSeconds(3600)), old.bindingId());
        // NEW binding (fresh start attempt): active, camera connected with a
        // verified track — must win over the revoked history.
        attempt = startFor(student);
        var newest = insertPreflightAndBinding(jdbc, sessionId, student, attempt, base, null);
        jdbc.update("UPDATE monitoring_media_bindings SET active_from = ? WHERE id = ?",
                java.sql.Timestamp.from(base), newest.bindingId());
        insertOpenCameraConnection(jdbc, newest.bindingId(), "track-new", base);

        MonitoringOverviewResponse overview = dashboard.overview(teacherId, sessionId);
        assertThat(overview.camera().revoked()).as("old binding must not count").isZero();
        assertThat(overview.camera().connected()).isEqualTo(1);
        assertThat(overview.camera().publishing()).isEqualTo(1);

        var page = dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        assertThat(page.items()).hasSize(2);
        long newestAttemptId = attempt;
        var newestRow = page.items().stream()
                .filter(i -> i.attemptId().equals(newestAttemptId)).findFirst().orElseThrow();
        assertThat(newestRow.camera().state()).isEqualTo("CONNECTED");
        assertThat(newestRow.camera().publishing()).isTrue();
        assertThat(newestRow.camera().online()).isTrue();
    }

    // === F04: signal separation ===

    @Test
    void connectedWithoutVerifiedTrackIsOnlineButNotPublishing() {
        long student = newUser(jdbc, "hard-f04a");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", student, sessionId, teacherId);
        long attempt = startFor(student);
        var binding = insertPreflightAndBinding(jdbc, sessionId, student, attempt, base, null);
        // Open connection WITHOUT track_sid — transport present, no verified
        // published track (the P4-B schema allows it; preflight verify owns
        // track verification).
        jdbc.update("INSERT INTO monitoring_camera_connections "
                + "(id, binding_id, connected_at, last_verified_at) VALUES (?,?,?,?)",
                UUID.randomUUID(), binding.bindingId(),
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base));

        var page = dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        var camera = page.items().get(0).camera();
        assertThat(camera.state()).isEqualTo("CONNECTED");
        assertThat(camera.online()).as("transport present").isTrue();
        assertThat(camera.publishing()).as("no verified track").isFalse();

        MonitoringOverviewResponse overview = dashboard.overview(teacherId, sessionId);
        assertThat(overview.camera().connected()).isEqualTo(1);
        assertThat(overview.camera().publishing()).as("connected != publishing").isZero();
    }

    @Test
    void aiSignalsSeparateEnabledDetectionsQuarantinedFromAlerts() {
        long student = newUser(jdbc, "hard-f04b");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", student, sessionId, teacherId);
        long attempt = startFor(student);
        insertPreflightAndBinding(jdbc, sessionId, student, attempt, base, null);
        // policy row ABSENT here (legacy session) → ai.enabled must read
        // false and nothing may 500; then enable AI + seed signals.
        var legacyPage = dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        assertThat(legacyPage.items().get(0).ai().enabled()).isFalse();

        jdbc.update("INSERT INTO session_monitoring_policies "
                + "(exam_session_id, monitoring_enabled, ai_detection_enabled) "
                + "VALUES (?, true, true)", sessionId);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO monitoring_ai_detections "
                    + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                    + "model_id, model_version, runtime, observed_at, quarantined, payload, "
                    + "frame_width, frame_height, processing_ms, client_sequence) "
                    + "VALUES (?,?,?,?,'CLIENT_MODEL','PHONE_OBSERVED','yolo26s','8.4.64-onnx',"
                    + "'ORT_WEB_WASM',?,false,'[]'::jsonb,640,480,40,?)",
                    UUID.randomUUID(), attempt, sessionId, student,
                    java.sql.Timestamp.from(base.plusSeconds(i)), i + 1);
        }
        jdbc.update("INSERT INTO monitoring_ai_detections "
                + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                + "model_id, model_version, runtime, observed_at, quarantined, "
                + "quarantine_reason, payload, frame_width, frame_height, processing_ms, "
                + "client_sequence) VALUES (?,?,?,?,'CLIENT_MODEL','PHONE_OBSERVED','yolo26s',"
                + "'8.4.64-onnx','ORT_WEB_WASM',?,true,'LATE','[]'::jsonb,640,480,40,99)",
                UUID.randomUUID(), attempt, sessionId, student,
                java.sql.Timestamp.from(base.plusSeconds(5)));

        var page = dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        var ai = page.items().get(0).ai();
        assertThat(ai.enabled()).isTrue();
        assertThat(ai.detections()).isEqualTo(3);
        assertThat(ai.quarantined()).isEqualTo(1);
        assertThat(ai.totalAlerts()).isZero(); // detections ≠ alerts
        // Teacher subscription stays honestly NOT_AVAILABLE (F04).
        assertThat(page.teacherSubscriptionState()).isEqualTo("NOT_AVAILABLE");
    }

    @Test
    void legacySessionWithoutPolicyRowNeverFails() {
        long student = newUser(jdbc, "hard-legacy");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", student, sessionId, teacherId);
        startFor(student);

        MonitoringOverviewResponse overview = dashboard.overview(teacherId, sessionId);
        assertThat(overview.monitoringEnabled()).isFalse();
        assertThat(overview.ai().enabled()).isFalse();
        var page = dashboard.participants(teacherId, sessionId, null, 50, null, null, null);
        assertThat(page.items()).hasSize(1);
    }
}
