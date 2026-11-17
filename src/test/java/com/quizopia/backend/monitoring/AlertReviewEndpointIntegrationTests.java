package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoringWithPolicy;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * P4-E HTTP surface tests (issue #96): Idempotency-Key enforcement, error
 * envelope mapping (409 carries MONITORING_REVIEW_VERSION_CONFLICT) and
 * permission rejection over the wire.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class AlertReviewEndpointIntegrationTests {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private MockMvc mockMvc;
    private Instant base;
    private long teacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private long alertId;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
        base = Instant.parse("2026-11-17T12:00:00Z");
        clock.setInstant(base);
        teacherId = newTeacherUser(jdbc, "endpointteacher" + "-" + UUID.randomUUID().toString().substring(0, 8));
        studentId = newUser(jdbc, "endpointstudent" + "-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, base);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", studentId, sessionId, teacherId);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        enableMonitoringWithPolicy(jdbc, sessionId, true, false, false);
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
        alertId = raiseAlert();
    }

    private long raiseAlert() {
        jdbc.update("INSERT INTO monitoring_ai_detections "
                + "(event_id, attempt_id, exam_session_id, user_id, provenance, event_type, "
                + "model_id, model_version, runtime, observed_at, quarantined, payload, "
                + "frame_width, frame_height, processing_ms, client_sequence) "
                + "VALUES (?,?,?,?,'CLIENT_MODEL','PHONE_OBSERVED','yolo26s','8.4.64-onnx',"
                + "'ORT_WEB_WASM',?,false,'[]'::jsonb,640,480,40,1)",
                UUID.randomUUID(), attemptId, sessionId, studentId,
                java.sql.Timestamp.from(base));
        jdbc.update("INSERT INTO monitoring_ai_alerts "
                + "(attempt_id, exam_session_id, user_id, alert_type, status, "
                + "first_detected_at, last_detected_at, detection_count, model_id, "
                + "model_version, provenance) "
                + "VALUES (?,?,?,'PHONE_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                + "'CLIENT_MODEL')",
                attemptId, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base));
        return jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class, attemptId);
    }

    @Test
        void reviewRoundTripOverHttp() throws Exception {
        mockMvc.perform(post("/api/monitoring/alerts/{alertId}/reviews", alertId)
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(teacherId))))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"action\":\"FALSE_POSITIVE\","
                                + "\"note\":\"http artefact\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(get("/api/monitoring/alerts/{alertId}/reviews", alertId)
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(teacherId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentReview.status").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.history[0].note").value("http artefact"));
    }

    @Test
        void versionConflictMapsTo409WithCode() throws Exception {
        // version already moved to 1 by a first review
        jdbc.update("INSERT INTO monitoring_alert_reviews "
                + "(alert_id, reviewer_user_id, action, review_version, idempotency_key) "
                + "VALUES (?,?, 'MARK_VIEWED', 1, ?)", alertId, teacherId, UUID.randomUUID());
        jdbc.update("UPDATE monitoring_ai_alerts SET review_status='VIEWED', review_version=1, "
                + "reviewed_by=?, reviewed_at=? WHERE id=?",
                teacherId, java.sql.Timestamp.from(base), alertId);

        mockMvc.perform(post("/api/monitoring/alerts/{alertId}/reviews", alertId)
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(teacherId))))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"action\":\"NOTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_REVIEW_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("current version 1")));
    }

    @Test
        void missingIdempotencyKeyRejected() throws Exception {
        mockMvc.perform(post("/api/monitoring/alerts/{alertId}/reviews", alertId)
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(teacherId))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"action\":\"NOTED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
        void studentGets403OverHttp() throws Exception {
        mockMvc.perform(post("/api/monitoring/alerts/{alertId}/reviews", alertId)
                        .with(jwt().jwt(j -> j.claim("sub", String.valueOf(studentId))))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"action\":\"NOTED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MONITORING_ACCESS_DENIED"));
        Map<String, Object> count = jdbc.queryForMap(
                "SELECT COUNT(*) AS n FROM monitoring_alert_reviews WHERE alert_id = ?", alertId);
        assertThat(((Number) count.get("n")).longValue()).isZero();
    }
}
