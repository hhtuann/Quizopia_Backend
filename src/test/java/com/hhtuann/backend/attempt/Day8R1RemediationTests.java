package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptResultService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.application.SessionResultService;
import com.hhtuann.backend.attempt.application.SessionStatisticsService;
import com.hhtuann.backend.attempt.dto.AttemptResultResponse;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 8 R1 remediation tests: role-aware result, query validation, statistics correctRate, Excel date cell.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class Day8R1RemediationTests {

    @Autowired private AttemptResultService resultService;
    @Autowired private AttemptService attemptService;
    @Autowired private AttemptSubmitService submitService;
    @Autowired private SessionResultService sessionResultService;
    @Autowired private SessionStatisticsService statsService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long teacherUserId;
    private long studentUserId;
    private long sessionId;
    private long attemptId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        teacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tch" + tag + "','t" + tag + "@t.com','h','Teacher')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");
        studentUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu" + tag + "','s" + tag + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + sr + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + school + ",'TC" + tag + "')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'B','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'E','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacherUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, sessionId, new com.hhtuann.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
    }

    // FINDING 1: Role-aware attempt result
    @Test
    void teacherOwnerCanViewAttemptResult() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r1-key-" + tag));
        em.flush(); em.clear();
        AttemptResultResponse r = resultService.getAttemptResult(teacherUserId, "TEACHER", attemptId);
        assertThat(r.attemptId()).isEqualTo(attemptId);
    }

    @Test
    void nonOwningTeacherDeniedAttemptResult() {
        long otherTeacher = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot" + tag + "','ot@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + otherTeacher + "," + tr + ")");
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r1b-key-" + tag));
        em.flush(); em.clear();
        assertThatThrownBy(() -> resultService.getAttemptResult(otherTeacher, "TEACHER", attemptId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void systemAdminCanViewAnyAttemptResult() {
        long admin = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('adm" + tag + "','adm@t.com','h','Admin')");
        long ar = jdbc.queryForObject("SELECT id FROM roles WHERE code='SYSTEM_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + admin + "," + ar + ")");
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r1c-key-" + tag));
        em.flush(); em.clear();
        AttemptResultResponse r = resultService.getAttemptResult(admin, "SYSTEM_ADMIN", attemptId);
        assertThat(r.attemptId()).isEqualTo(attemptId);
    }

    // FINDING 3: Query validation
    @Test
    void invalidDirectionRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, "DIAGONAL", null, null, null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void negativeMinPercentageRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, new BigDecimal("-1"), null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void maxPercentageOver100Rejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, new BigDecimal("101")))
                .isInstanceOf(AttemptException.class);
    }

    // FINDING 3: Query validation — exact error code assertions
    @Test
    void invalidSortRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, "rawSQL", null, null, null, null))
                .isInstanceOf(AttemptException.class)
                .extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    @Test
    void negativePageRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, -1, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    @Test
    void zeroSizeRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 0, null, null, null, null, null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    @Test
    void sizeOver100Rejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 101, null, null, null, null, null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    @Test
    void minGreaterThanMaxRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, new BigDecimal("80"), new BigDecimal("20")))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    // R2: Fail-closed — unsupported/null roles denied
    @Test
    void unsupportedRoleDeniedAttemptResult() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r2unsup-" + tag));
        em.flush(); em.clear();
        assertThatThrownBy(() -> resultService.getAttemptResult(teacherUserId, "GHOST_ROLE", attemptId))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.RESULT_ACCESS_DENIED);
    }

    @Test
    void nullRoleDeniedAttemptResult() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r2null-" + tag));
        em.flush(); em.clear();
        assertThatThrownBy(() -> resultService.getAttemptResult(teacherUserId, null, attemptId))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.RESULT_ACCESS_DENIED);
    }

    @Test
    void nullRoleDeniedSessionResults() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, null, sessionId, 0, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
    }

    @Test
    void unsupportedRoleDeniedSessionResults() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "GHOST", sessionId, 0, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.SESSION_RESULTS_ACCESS_DENIED);
    }
    @Test
    void correctRateIsCorrectOverAnswered() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest("r1stat-" + tag));
        em.flush(); em.clear();
        var stats = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        // Student unanswered → answeredCount=0 → correctRate=null
        assertThat(stats.perQuestionStatistics()).hasSize(1);
        assertThat(stats.perQuestionStatistics().get(0).answeredCount()).isEqualTo(0);
        assertThat(stats.perQuestionStatistics().get(0).correctRate()).isNull();
    }

    // R3 FINDING 1: minPct > 100 and maxPct < 0
    @Test
    void minPercentageOver100Rejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, new BigDecimal("101"), null))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    @Test
    void maxPercentageBelowZeroRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, new BigDecimal("-1")))
                .isInstanceOf(AttemptException.class).extracting(e -> ((AttemptException) e).getErrorCode())
                .isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.INVALID_RESULT_QUERY);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
