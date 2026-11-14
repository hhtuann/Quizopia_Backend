package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.SessionAttemptsService;
import com.quizopia.backend.monitoring.dto.SessionAttemptsResponse;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-A4 (#83): teacher attempt listing for the Activity-panel picker — must
 * include IN_PROGRESS attempts (the graded best-results report misses live
 * ones, found by the browser smoke run) and authorize like the timeline /
 * STOMP topic gate.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SessionAttemptsServiceIntegrationTests {

    @Autowired private SessionAttemptsService service;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.quizopia.backend.testsupport.MutableClock clock;

    private long teacherId;
    private long studentId;
    private long sessionId;
    private long examId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        String tag = "sat" + System.nanoTime() % 1_000_000;
        teacherId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('t" + tag + "','t" + tag + "@t.com','h','T')");
        grant(teacherId, "TEACHER");
        studentId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s" + tag + "','s" + tag + "@t.com','h','S')");
        grant(studentId, "STUDENT");
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'" + tag + "M','M')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherId + "," + subj + ",'B','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacherId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherId + ")");
        examId = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherId + "," + subj + ",'E','E','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eq + ",'A','a',true,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',false,2),(" + eq + ",'D','d',false,3)");
        long cls = ins("INSERT INTO classrooms (owner_user_id, code, name) VALUES (" + teacherId + ",'C" + tag + "','C')");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + cls + "," + studentId + ")");
        sessionId = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + examId + ",'SS" + tag + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherId + ",'" + now.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES (" + sessionId + "," + cls + ")");
    }

    @Test
    void includesInProgressAttemptForOwnerTeacher() {
        long attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();

        SessionAttemptsResponse response = service.attempts(teacherId, sessionId);
        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().get(0).attemptId()).isEqualTo(attemptId);
        assertThat(response.attempts().get(0).status()).isEqualTo("IN_PROGRESS");
        assertThat(response.attempts().get(0).userId()).isEqualTo(studentId);
        assertThat(response.attempts().get(0).attemptNumber()).isEqualTo(1);
        assertThat(response.attempts().get(0).startedAt()).isNotNull();
        assertThat(response.attempts().get(0).deadlineAt()).isNotNull();
        assertThat(response.attempts().get(0).submittedAt()).isNull();
    }

    @Test
    void includesSubmittedAttemptStatus() {
        long attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K' WHERE id=" + attemptId);

        SessionAttemptsResponse response = service.attempts(teacherId, sessionId);
        assertThat(response.attempts()).extracting(SessionAttemptsResponse.Item::status)
                .containsExactly("SUBMITTED");
    }

    @Test
    void otherTeacherDenied() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o" + System.nanoTime() % 1000 + "','o@t.com','h','O')");
        grant(other, "TEACHER");
        assertThatThrownBy(() -> service.attempts(other, sessionId))
                .isInstanceOf(com.quizopia.backend.monitoring.exception.MonitoringException.class)
                .satisfies(e -> assertThat(((com.quizopia.backend.monitoring.exception.MonitoringException) e)
                        .getErrorCode().code()).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void studentDenied() {
        assertThatThrownBy(() -> service.attempts(studentId, sessionId))
                .isInstanceOf(com.quizopia.backend.monitoring.exception.MonitoringException.class);
    }

    // === helpers ===

    private void grant(long userId, String role) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + userId + ", id FROM roles WHERE code='" + role + "'");
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
