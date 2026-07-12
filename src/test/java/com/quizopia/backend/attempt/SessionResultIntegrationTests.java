package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.application.SessionResultService;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 8 Gate C — teacher/admin session results (BEST per student + authorization). Uses the real
 * PostgreSQL CTE + ROW_NUMBER() BEST query; verifies one row per student, authorization matrix, and pagination.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SessionResultIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private SessionResultService sessionResultService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long teacherUserId;
    private long studentUserId;
    private long sessionId;
    private long studentAttemptId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        Instant now = Instant.parse("2026-07-06T08:00:00Z");

        teacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tch-" + tag + "','t" + tag + "@t.com','h','Teacher')");
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + teacherRoleId + ")");

        studentUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu-" + tag + "','s" + tag + "@t.com','h','Student " + tag + "')");
        long studentRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + studentRoleId + ")");

        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + school + ",'TC" + tag + "')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'E" + tag + "','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacherUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC" + tag + "','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        studentAttemptId = attemptService.startAttempt(studentUserId, sessionId, new com.quizopia.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
    }

    @Test
    void teacherOwnerSeesOneBestRowPerStudent() {
        submitService.submitAttempt(studentUserId, studentAttemptId, new SubmitRequest("sess-key-" + tag));
        em.flush(); em.clear();
        var page = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
        var row = page.items().get(0);
        assertThat(row.bestAttemptId()).isEqualTo(studentAttemptId);
        assertThat(row.studentCode()).isEqualTo("SC" + tag);
        assertThat(row.displayName()).contains("Student");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.score()).isEqualByComparingTo("0"); // unanswered → 0
        assertThat(row.percentage()).isEqualByComparingTo("0.00");
        assertThat(row.gradeStatus()).isEqualTo("AUTO_GRADED");
        // No answer key in the DTO
        assertThat(row.toString()).doesNotContain("answerKey").doesNotContain("expectedAnswer");
    }

    @Test
    void studentDeniedSessionResults() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(studentUserId, "STUDENT", sessionId, 0, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void nonOwningTeacherDenied() {
        // Create a different teacher
        long otherTeacher = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot-" + tag + "','ot" + tag + "@t.com','h','OtherTeacher')");
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + otherTeacher + "," + teacherRoleId + ")");
        assertThatThrownBy(() -> sessionResultService.getSessionResults(otherTeacher, "TEACHER", sessionId, 0, 20, null, null, null, null, null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void invalidPageSizeRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 101, null, null, null, null, null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void invalidSortFieldRejected() {
        assertThatThrownBy(() -> sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, "rawSQL", null, null, null, null))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void emptySessionReturnsZeroResults() {
        var page = sessionResultService.getSessionResults(teacherUserId, "TEACHER", sessionId, 0, 20, null, null, null, null, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(0);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
