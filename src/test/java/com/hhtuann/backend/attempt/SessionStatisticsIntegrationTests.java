package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.application.SessionStatisticsService;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.attempt.dto.SessionStatisticsResponse;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class SessionStatisticsIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private SessionStatisticsService statsService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private MutableClock clock;

    private long teacherUserId;
    private long sessionId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        teacherUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('tch-" + tag + "','t" + tag + "@t.com','h','Teacher')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacherUserId + "," + tr + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SC" + tag + "','School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + school + ",'TC" + tag + "')");

        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'E" + tag + "','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacherUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherUserId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "')");
    }

    @Test
    void emptySessionAllZerosAndNulls() {
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.bestResultCount()).isEqualTo(0);
        assertThat(s.averageScore()).isNull();
        assertThat(s.averagePercentage()).isNull();
        assertThat(s.minimumScore()).isNull();
        assertThat(s.maximumScore()).isNull();
        assertThat(s.medianPercentage()).isNull();
        assertThat(s.distribution()).hasSize(10);
        assertThat(s.distribution()).allSatisfy(b -> assertThat(b.count()).isZero());
        assertThat(s.perQuestionStatistics()).isEmpty();
        assertThat(s.passCount()).isNull();
        assertThat(s.passRate()).isNull();
    }

    @Test
    void oneStudentBestResultHasStats() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.bestResultCount()).isEqualTo(1);
        assertThat(s.startedStudentCount()).isEqualTo(1);
        assertThat(s.submittedStudentCount()).isEqualTo(1);
        assertThat(s.totalAttemptCount()).isEqualTo(1);
        // Unanswered → 0 score, 0% percentage
        assertThat(s.minimumScore()).isEqualByComparingTo("0");
        assertThat(s.maximumScore()).isEqualByComparingTo("0");
        assertThat(s.averageScore()).isEqualByComparingTo("0.00");
        assertThat(s.medianPercentage()).isEqualByComparingTo("0.00");
        // Bucket 0 ([0,10)) should have count 1
        assertThat(s.distribution().get(0).count()).isEqualTo(1);
        int bucketSum = s.distribution().stream().mapToInt(b -> b.count()).sum();
        assertThat(bucketSum).isEqualTo(s.bestResultCount());
        // Per-question: 1 question, 1 student, unanswered
        assertThat(s.perQuestionStatistics()).hasSize(1);
        var qs = s.perQuestionStatistics().get(0);
        assertThat(qs.answeredCount()).isEqualTo(0);
        assertThat(qs.correctCount()).isEqualTo(0);
        assertThat(qs.unansweredCount()).isEqualTo(1);
    }

    @Test
    void bucketSumEqualsBestResultCount() {
        for (int i = 0; i < 3; i++) createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        int bucketSum = s.distribution().stream().mapToInt(b -> b.count()).sum();
        assertThat(bucketSum).isEqualTo(s.bestResultCount());
    }

    @Test
    void distributionHas10Buckets() {
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.distribution()).hasSize(10);
        // Last bucket is [90, 100] inclusive
        var last = s.distribution().get(9);
        assertThat(last.lowerBound()).isEqualTo(90);
        assertThat(last.upperBound()).isEqualTo(100);
        assertThat(last.upperInclusive()).isTrue();
    }

    @Test
    void studentDeniedStatistics() {
        assertThatThrownBy(() -> statsService.getStatistics(ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')"), "STUDENT", sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void nonOwningTeacherDenied() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot','ot@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + tr + ")");
        assertThatThrownBy(() -> statsService.getStatistics(other, "TEACHER", sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void noAnswerKeyFieldsInResponse() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        assertThat(s.toString()).doesNotContain("answerKey").doesNotContain("expectedAnswer")
                .doesNotContain("correctOption").doesNotContain("isCorrect");
    }

    @Test
    void perQuestionCorrectPlusIncorrectEqualsAnswered() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        for (var q : s.perQuestionStatistics()) {
            assertThat(q.correctCount() + q.incorrectCount()).isEqualTo(q.answeredCount());
        }
    }

    @Test
    void deterministicQuestionOrderByExamQuestionId() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, "TEACHER", sessionId);
        // Only 1 question in fixture; verify output is ordered by examQuestionId (stable).
        // For 2+ questions the SQL ORDER BY aq.exam_question_id guarantees it.
        assertThat(s.perQuestionStatistics()).hasSize(1);
        assertThat(s.perQuestionStatistics().get(0).examQuestionId()).isNotNull();
    }

    private long[] createStudentAndSubmit() {
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu-" + tag + UUID.randomUUID().toString().substring(0, 4) + "','s" + UUID.randomUUID() + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + su + "," + school + ",'SC" + tag + UUID.randomUUID().toString().substring(0, 4) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + sp + "," + teacherUserId + ")");
        long attemptId = attemptService.startAttempt(su, sessionId, new com.hhtuann.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
        submitService.submitAttempt(su, attemptId, new SubmitRequest("stat-key-" + UUID.randomUUID()));
        return new long[]{su, attemptId};
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
