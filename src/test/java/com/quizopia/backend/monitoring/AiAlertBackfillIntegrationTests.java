package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AiAlertQueryService;
import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.dto.AiAlertPageResponse;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import com.quizopia.backend.monitoring.exception.MonitoringException;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teacher-side AI alert backfill (P4-C, issue #90, CONTRACT §5/§7):
 * {@code GET /api/exam-sessions/{sessionId}/ai-alerts} — service-level
 * auth matrix (AI_ALERTS_READ + session owner; other teacher 403, student
 * 403, revoked permission 403) and the paging contract (newest-first by
 * alertId, opaque-numeric cursor, optional OPEN/CLOSED status filter,
 * student displayName joined in).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AiAlertBackfillIntegrationTests {

    @Autowired private AiAlertQueryService alertQueryService;
    @Autowired private AiDetectionIngestService ingestService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long teacherId;
    private long otherTeacherId;
    private long studentId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T09:00:00Z");
        clock.setInstant(base);
        teacherId = newTeacher("aiteacher");
        otherTeacherId = newTeacher("aiother");
        studentId = newUser(jdbc, "aialertstudent");
        // Session OWNED by the teacher; the student participates.
        sessionId = insertOpenSession(jdbc, teacherId, base);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (" + studentId + "," + sessionId + "," + teacherId + ")");
    }

    private long startMonitoredAttempt() {
        long attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
        return attemptId;
    }

    private void ingest(Instant observedAt, String eventType) {
        AiDetectionBatchRequest.Item item = new AiDetectionBatchRequest.Item(
                UUID.randomUUID(), eventType, observedAt, 1L, "yolo26s", "8.4.64-onnx",
                "ORT_WEB_WASM", 640, 480, 40, List.of());
        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId,
                jdbc.queryForObject("SELECT id FROM attempts ORDER BY id DESC LIMIT 1", Long.class),
                new AiDetectionBatchRequest(List.of(item)));
        assertThat(r.acceptedCount()).isEqualTo(1);
    }

    @Test
    void teacherOwnerSeesNewestFirstAlertsWithStudentName() {
        long attempt = startMonitoredAttempt();
        ingest(base, "PHONE_OBSERVED");
        clock.advance(Duration.ofSeconds(30));
        ingest(base.plusSeconds(30), "SCREEN_OBSERVED");

        AiAlertPageResponse page = alertQueryService.alerts(teacherId, sessionId, null, 50, null);
        assertThat(page.items()).hasSize(2);
        // newest-first by id: SCREEN window opened later
        assertThat(page.items().get(0).alertType()).isEqualTo("SCREEN_DETECTED");
        assertThat(page.items().get(1).alertType()).isEqualTo("PHONE_DETECTED");
        assertThat(page.items().get(0).attemptId()).isEqualTo(attempt);
        assertThat(page.items().get(0).studentUserId()).isEqualTo(studentId);
        assertThat(page.items().get(0).studentName())
                .as("displayName joined in for the teacher feed")
                .isEqualTo("AIALERTSTUDENT");
        assertThat(page.items().get(0).status()).isEqualTo("OPEN");
        assertThat(page.items().get(0).detectionCount()).isEqualTo(1);
        assertThat(page.items().get(0).modelId()).isEqualTo("yolo26s");
        assertThat(page.items().get(0).provenance()).isEqualTo("CLIENT_MODEL");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void cursorPagesNewestFirstAndStatusFilterApplies() {
        startMonitoredAttempt();
        ingest(base, "PHONE_OBSERVED");
        clock.advance(Duration.ofSeconds(30));
        ingest(base.plusSeconds(30), "PHONE_OBSERVED"); // gap>20s → second window
        ingest(base.plusSeconds(31), "PERSON_NOT_OBSERVED");

        AiAlertPageResponse first = alertQueryService.alerts(teacherId, sessionId, null, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        AiAlertPageResponse second = alertQueryService.alerts(teacherId, sessionId,
                first.nextCursor(), 2, null);
        assertThat(second.items()).hasSize(1);
        Long newest = first.items().get(0).alertId();
        Long oldest = second.items().get(0).alertId();
        assertThat(oldest).isLessThan(newest);

        // Status filter: only the two OPEN windows of PHONE + PERSON types…
        AiAlertPageResponse openOnly = alertQueryService.alerts(teacherId, sessionId, null, 50,
                "OPEN");
        assertThat(openOnly.items()).hasSize(2);
        // …while the CLOSED first PHONE window is filterable too.
        AiAlertPageResponse closedOnly = alertQueryService.alerts(teacherId, sessionId, null, 50,
                "CLOSED");
        assertThat(closedOnly.items()).hasSize(1);
        assertThat(closedOnly.items().get(0).alertType()).isEqualTo("PHONE_DETECTED");
    }

    @Test
    void otherTeacherDenied403() {
        startMonitoredAttempt();
        ingest(base, "PHONE_OBSERVED");
        assertThatThrownBy(() -> alertQueryService.alerts(otherTeacherId, sessionId, null, 50,
                null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void studentDenied403() {
        startMonitoredAttempt();
        ingest(base, "PHONE_OBSERVED");
        assertThatThrownBy(() -> alertQueryService.alerts(studentId, sessionId, null, 50, null))
                .as("students never read alerts (AI_ALERTS_READ is TEACHER-only)")
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void teacherWithoutAlertsReadPermissionDenied403() {
        startMonitoredAttempt();
        ingest(base, "PHONE_OBSERVED");
        jdbc.update("DELETE FROM role_permissions WHERE role_id="
                + "(SELECT id FROM roles WHERE code='TEACHER') AND permission_id="
                + "(SELECT id FROM permissions WHERE code='AI_ALERTS_READ')");
        assertThatThrownBy(() -> alertQueryService.alerts(teacherId, sessionId, null, 50, null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void missingSessionDenied403WithoutExistenceLeak() {
        assertThatThrownBy(() -> alertQueryService.alerts(teacherId, 999999L, null, 50, null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void malformedCursorRejected400() {
        assertThatThrownBy(() -> alertQueryService.alerts(teacherId, sessionId, "not-a-number",
                50, null))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_CURSOR_INVALID"));
    }

    @Test
    void unknownStatusFilterRejected400() {
        assertThatThrownBy(() -> alertQueryService.alerts(teacherId, sessionId, null, 50,
                "ARCHIVED"))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode().code())
                        .isEqualTo("MONITORING_VALIDATION_ERROR"));
    }

    // === helpers ===

    private long newTeacher(String username) {
        long id = newUser(jdbc, username);
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'",
                Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + id + "," + roleId + ")");
        return id;
    }
}
