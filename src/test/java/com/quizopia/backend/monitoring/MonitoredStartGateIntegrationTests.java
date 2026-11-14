package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.monitoring.application.CameraPreflightService;
import com.quizopia.backend.monitoring.dto.PreflightStartResponse;
import com.quizopia.backend.monitoring.dto.PreflightVerifyResponse;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-B2 monitored-start gate (issue #85): the createNewAttempt lease
 * consumption replacing the P4-A fail-closed throw. NOT @Transactional —
 * fixtures commit so the concurrent double-start test sees real independent
 * transactions (rows accumulate in the throwaway container; unique tags
 * keep tests independent).
 *
 * <p>Covers: lease required/invalid/used/expired/wrong-user, successful
 * consumption (attempt + binding + CONSUMED preflight atomically), lease
 * reuse after submit, legacy unmonitored lease ignoring, resume bypassing
 * the gate with the camera down, and the concurrent double-start
 * single-attempt guarantee.
 */
@SpringBootTest(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-key=testkey",
        "quizopia.media.livekit.api-secret=test-livekit-secret-0123456789abcdef012345"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class, MediaGatewayTestConfig.class})
class MonitoredStartGateIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private FakeMediaRoomGateway gateway;
    @Autowired private LiveKitProperties liveKit;
    @Autowired private CameraPreflightService preflightService;
    @Autowired private AttemptService attemptService;

    private Instant now;
    private long teacherId;
    private long studentId;
    private long secondStudentId;
    private long monitoredSessionId;
    private long unmonitoredSessionId;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-14T09:00:00Z");
        clock.setInstant(now);
        gateway.reset();
        String tag = "msg" + System.nanoTime() % 1_000_000;
        teacherId = user("t" + tag);
        grant(teacherId, "TEACHER");
        studentId = user("a" + tag);
        grant(studentId, "STUDENT");
        secondStudentId = user("b" + tag);
        grant(secondStudentId, "STUDENT");
        long examId = exam(tag, teacherId);
        monitoredSessionId = session(examId, teacherId, "SM" + tag);
        jdbc.update("INSERT INTO session_monitoring_policies (exam_session_id, monitoring_enabled) "
                + "VALUES (?, true)", monitoredSessionId);
        memberAll(tag, monitoredSessionId, studentId, secondStudentId);
        unmonitoredSessionId = session(examId, teacherId, "SU" + tag);
        memberAll(tag + "u", unmonitoredSessionId, studentId, secondStudentId);
    }

    @Test
    void monitoredStartWithoutLeaseIsRefused() {
        assertThatThrownBy(() -> attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null)))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_REQUIRED"));
        assertThat(attemptCount(monitoredSessionId)).isZero();
    }

    @Test
    void garbageLeaseIsInvalid() {
        assertThatThrownBy(() -> attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, "garbage-lease")))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_INVALID"));
        assertThat(attemptCount(monitoredSessionId)).isZero();
    }

    @Test
    void validLeaseCreatesAttemptAndBindsItAtomically() {
        String lease = readinessLease(studentId);
        StartAttemptResponse response = attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, lease));

        assertThat(response.resumed()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM attempts WHERE id=?", String.class, response.attemptId()))
                .isEqualTo("IN_PROGRESS");
        // Binding attached to the exact attempt; preflight CONSUMED once.
        assertThat(jdbc.queryForObject(
                "SELECT b.attempt_id FROM monitoring_media_bindings b "
                        + "JOIN monitoring_preflights p ON p.id = b.preflight_id "
                        + "WHERE p.user_id = ? AND p.status = 'CONSUMED'",
                Long.class, studentId)).isEqualTo(response.attemptId());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_preflights WHERE user_id=?",
                String.class, studentId)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at FROM monitoring_preflights WHERE user_id=?",
                java.sql.Timestamp.class, studentId).toInstant()).isEqualTo(now);
    }

    @Test
    void leaseIsSingleUseEvenAfterTheFirstAttemptWasSubmitted() {
        String lease = readinessLease(studentId);
        StartAttemptResponse first = attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, lease));
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), "
                + "submission_idempotency_key='K' WHERE id=" + first.attemptId());

        assertThatThrownBy(() -> attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, lease)))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_USED"));
        assertThat(attemptCount(monitoredSessionId)).isEqualTo(1);
    }

    @Test
    void leaseOfAnotherUserIsInvalid() {
        String lease = readinessLease(studentId);
        assertThatThrownBy(() -> attemptService.startAttempt(
                secondStudentId, monitoredSessionId, new StartAttemptRequest(null, lease)))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_INVALID"));
        assertThat(attemptCount(monitoredSessionId)).isZero();
    }

    @Test
    void expiredLeaseIsRejected() {
        String lease = readinessLease(studentId);
        clock.advance(liveKit.getReadinessLeaseTtl().plusSeconds(1));
        assertThatThrownBy(() -> attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, lease)))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_EXPIRED"));
        assertThat(attemptCount(monitoredSessionId)).isZero();
    }

    @Test
    void unmonitoredSessionIgnoresGarbageLeaseSilently() {
        // Legacy compatibility: the lease field is optional noise on
        // unmonitored sessions — byte-for-byte legacy behaviour.
        StartAttemptResponse response = attemptService.startAttempt(
                studentId, unmonitoredSessionId, new StartAttemptRequest(null, "garbage-lease"));
        assertThat(response.resumed()).isFalse();
        assertThat(attemptCount(unmonitoredSessionId)).isEqualTo(1);
    }

    @Test
    void resumeBypassesGateWhenCameraIsDown() {
        String lease = readinessLease(studentId);
        StartAttemptResponse first = attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null, lease));
        gateway.reset(); // camera gone entirely — no lease, resume must work

        StartAttemptResponse resumed = attemptService.startAttempt(
                studentId, monitoredSessionId, new StartAttemptRequest(null));
        assertThat(resumed.resumed()).isTrue();
        assertThat(resumed.attemptId()).isEqualTo(first.attemptId());
        assertThat(attemptCount(monitoredSessionId)).isEqualTo(1);
    }

    @Test
    void concurrentDoubleStartSpendsLeaseExactlyOnce() throws Exception {
        String lease = readinessLease(studentId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> call = () -> {
                start.await();
                try {
                    return attemptService.startAttempt(
                            studentId, monitoredSessionId, new StartAttemptRequest(null, lease));
                } catch (RuntimeException e) {
                    return e;
                }
            };
            Future<Object> a = pool.submit(call);
            Future<Object> b = pool.submit(call);
            start.countDown();

            List<Object> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            // Exactly ONE created attempt: the loser either hits
            // PREFLIGHT_USED or — when it acquires the session lock after
            // the winner's commit — takes the resume path (resumed=true).
            long created = results.stream()
                    .filter(r -> r instanceof StartAttemptResponse response && !response.resumed())
                    .count();
            assertThat(created).isEqualTo(1);
            for (Object result : results) {
                if (result instanceof RuntimeException e) {
                    assertThat(code(e)).isEqualTo("MONITORING_PREFLIGHT_USED");
                }
            }
            // Exactly ONE attempt row exists for (session, user).
            assertThat(attemptCount(monitoredSessionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM monitoring_preflights WHERE user_id=?",
                    String.class, studentId)).isEqualTo("CONSUMED");
        } finally {
            pool.shutdownNow();
        }
    }

    // === helpers ===

    /** Full learner flow against the fake gateway → raw readiness lease. */
    private String readinessLease(long userId) {
        PreflightStartResponse created = preflightService.createPreflight(userId, monitoredSessionId);
        String room = jdbc.queryForObject(
                "SELECT room_name FROM monitoring_media_rooms WHERE exam_session_id=?",
                String.class, monitoredSessionId);
        gateway.publishCamera(room, created.mediaIdentity(), "track-" + created.preflightId());
        PreflightVerifyResponse verified = preflightService.verifyPreflight(
                userId, monitoredSessionId, created.preflightId());
        assertThat(verified.state()).isEqualTo("READY");
        return verified.readinessLease();
    }

    private int attemptCount(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempts WHERE exam_session_id=? AND user_id=?",
                Integer.class, sessionId, studentId);
        return count == null ? 0 : count;
    }

    private static String code(Throwable e) {
        return ((MonitoringException) e).getErrorCode().code();
    }

    private long user(String username) {
        return ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('"
                + username + "','" + username + "@t.com','h','" + username + "')");
    }

    private void grant(long userId, String role) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + userId
                + ", id FROM roles WHERE code='" + role + "'");
    }

    private long exam(String tag, long owner) {
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'" + tag + "','M')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + owner + "," + subj + ",'B" + tag + "','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + owner + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + owner + ")");
        long examId = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, "
                + "total_points) VALUES (" + owner + "," + subj + ",'E" + tag + "','E','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, "
                + "metadata) VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq + ",'A','a',true,0),(" + eq + ",'B','b',false,1),("
                + eq + ",'C','c',false,2),(" + eq + ",'D','d',false,3)");
        return examId;
    }

    private long session(long examId, long owner, String code) {
        return ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, "
                + "created_by, opened_at, duration_minutes) VALUES (" + examId + ",'" + code + "','S','OPEN','"
                + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',0," + owner
                + ",'" + now.minusSeconds(3600) + "',60)");
    }

    private void memberAll(String tag, long sessionId, long... userIds) {
        long cls = ins("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'C" + tag + "','C')");
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES (" + sessionId + "," + cls + ")");
        List<Object[]> rows = new ArrayList<>();
        for (long userId : userIds) {
            rows.add(new Object[]{cls, userId});
        }
        jdbc.batchUpdate("INSERT INTO classroom_members (classroom_id, user_id) VALUES (?,?)", rows);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
