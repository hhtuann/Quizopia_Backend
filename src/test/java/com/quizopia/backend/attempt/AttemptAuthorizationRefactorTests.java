package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.attempt.exception.AttemptException;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the class-based visibility refactor of {@link AttemptService}: PUBLIC sessions are
 * visible/startable by any active student user; CLASS_RESTRICTED sessions only by assigned-class
 * members. Replaces the obsolete participant-based tests (blocked/not-found).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptAuthorizationRefactorTests {

    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long studentUserId;
    private long examId;
    private long publicSessionId;
    private long classRestrictedSessionId;
    private long classroomId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-10T08:00:00Z");
        clock.setInstant(now);
        String tag = java.util.UUID.randomUUID().toString().substring(0, 6);

        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('sar" + tag + "','sar" + tag + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + sr + ")");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");

        // Exam chain (1 SINGLE_CHOICE question with 4 options).
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + studentUserId + "," + subj + ",'B','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + studentUserId + ")");
        long examId = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, "
                + "published_at, total_points) VALUES (" + studentUserId + "," + subj
                + ",'E','Exam','PUBLISHED',now(),1)");
        this.examId = examId;
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");

        // PUBLIC session (DB default is PUBLIC, but explicit for clarity).
        publicSessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, created_by, opened_at, visibility, duration_minutes) VALUES (" + examId + ","
                + "'PUB','Public','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200)
                + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "','PUBLIC',60)");

        // CLASS_RESTRICTED session.
        classRestrictedSessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, "
                + "starts_at, ends_at, max_attempts, created_by, opened_at, visibility, duration_minutes) VALUES (" + examId + ","
                + "'CLS','ClassRestricted','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200)
                + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "','CLASS_RESTRICTED',60)");

        // Classroom owned by the exam creator (NOT yet assigned to the class-restricted session, student NOT yet a member).
        classroomId = insert("INSERT INTO classrooms (owner_user_id, code, name) "
                + "VALUES (" + studentUserId + ",'CLS-A','Class A')");
    }

    // ============================================================
    // AVAILABLE SESSIONS — visibility-based
    // ============================================================

    @Test
    void publicSessionVisibleToStudent() {
        AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
        assertThat(resp.items()).extracting(i -> i.sessionId()).contains(publicSessionId);
        assertThat(resp.items()).extracting(i -> i.sessionId()).doesNotContain(classRestrictedSessionId);
    }

    @Test
    void classRestrictedSessionVisibleToClassMember() {
        // Assign classroom to the CLASS_RESTRICTED session + add student as member.
        assignClassToSession(classroomId, classRestrictedSessionId);
        addMemberToClassroom(studentUserId, classroomId);

        AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
        assertThat(resp.items()).extracting(i -> i.sessionId()).contains(classRestrictedSessionId);
    }

    @Test
    void classRestrictedSessionHiddenFromNonMember() {
        // Assign classroom but DON'T add student as member.
        assignClassToSession(classroomId, classRestrictedSessionId);

        AvailableSessionsResponse resp = attemptService.getAvailableSessions(studentUserId);
        assertThat(resp.items()).extracting(i -> i.sessionId()).doesNotContain(classRestrictedSessionId);
    }

    @Test
    void otherUserCanSeePublicSession() {
        // PUBLIC sessions are platform-wide — any active student user can see them.
        long osUser = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('os','os@t.com','h','OS')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + osUser + "," + sr + ")");

        AvailableSessionsResponse resp = attemptService.getAvailableSessions(osUser);
        assertThat(resp.items()).extracting(i -> i.sessionId()).contains(publicSessionId);
    }

    // ============================================================
    // START ATTEMPT — visibility-based authorization
    // ============================================================

    @Test
    void startPublicSessionSucceedsForStudent() {
        StartAttemptResponse r = attemptService.startAttempt(studentUserId, publicSessionId, new StartAttemptRequest(null));
        assertThat(r.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void startClassRestrictedSucceedsForMember() {
        assignClassToSession(classroomId, classRestrictedSessionId);
        addMemberToClassroom(studentUserId, classroomId);

        StartAttemptResponse r = attemptService.startAttempt(studentUserId, classRestrictedSessionId, new StartAttemptRequest(null));
        assertThat(r.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void startClassRestrictedDeniedForNonMember() {
        // Student is NOT a member of the assigned class.
        assignClassToSession(classroomId, classRestrictedSessionId);

        assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, classRestrictedSessionId, new StartAttemptRequest(null)))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void startClassRestrictedDeniedWhenNoClassAssigned() {
        // CLASS_RESTRICTED session with NO classes assigned → nobody can start.
        assertThatThrownBy(() -> attemptService.startAttempt(studentUserId, classRestrictedSessionId, new StartAttemptRequest(null)))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(((AttemptException) e).getErrorCode().code()).isEqualTo("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void startPublicSessionSucceedsForOtherUser() {
        // PUBLIC is platform-wide: any active student user may start.
        long osUser = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('os2','os2@t.com','h','OS2')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + osUser + "," + sr + ")");

        StartAttemptResponse r = attemptService.startAttempt(osUser, publicSessionId, new StartAttemptRequest(null));
        assertThat(r.status()).isEqualTo("IN_PROGRESS");
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void assignClassToSession(long classroomId, long sessionId) {
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES ("
                + sessionId + "," + classroomId + ")");
    }

    private void addMemberToClassroom(long userId, long classroomId) {
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES ("
                + classroomId + "," + userId + ")");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
