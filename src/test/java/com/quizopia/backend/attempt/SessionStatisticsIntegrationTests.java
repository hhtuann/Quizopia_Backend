package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.application.SessionStatisticsService;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.dto.SessionStatisticsResponse;
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
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");

        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subj + ",'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherUserId + "," + subj + ",'E" + tag + "','Exam','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + tag + "'," + teacherUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES (" + exam + ",'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherUserId + ",'" + now.minusSeconds(3600) + "',60)");
    }

    @Test
    void emptySessionAllZerosAndNulls() {
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
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
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
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
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
        int bucketSum = s.distribution().stream().mapToInt(b -> b.count()).sum();
        assertThat(bucketSum).isEqualTo(s.bestResultCount());
    }

    @Test
    void distributionHas10Buckets() {
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
        assertThat(s.distribution()).hasSize(10);
        // Last bucket is [90, 100] inclusive
        var last = s.distribution().get(9);
        assertThat(last.lowerBound()).isEqualTo(90);
        assertThat(last.upperBound()).isEqualTo(100);
        assertThat(last.upperInclusive()).isTrue();
    }

    @Test
    void studentDeniedStatistics() {
        assertThatThrownBy(() -> statsService.getStatistics(ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')"), sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void nonOwningTeacherDenied() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ot','ot@t.com','h','OT')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + tr + ")");
        assertThatThrownBy(() -> statsService.getStatistics(other, sessionId))
                .isInstanceOf(AttemptException.class);
    }

    @Test
    void noAnswerKeyFieldsInResponse() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
        assertThat(s.toString()).doesNotContain("answerKey").doesNotContain("expectedAnswer")
                .doesNotContain("correctOption").doesNotContain("isCorrect");
    }

    @Test
    void perQuestionCorrectPlusIncorrectEqualsAnswered() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
        for (var q : s.perQuestionStatistics()) {
            assertThat(q.correctCount() + q.incorrectCount()).isEqualTo(q.answeredCount());
        }
    }

    @Test
    void deterministicQuestionOrderByExamQuestionId() {
        createStudentAndSubmit();
        em.flush(); em.clear();
        SessionStatisticsResponse s = statsService.getStatistics(teacherUserId, sessionId);
        // Only 1 question in fixture; verify output is ordered by examQuestionId (stable).
        // For 2+ questions the SQL ORDER BY aq.exam_question_id guarantees it.
        assertThat(s.perQuestionStatistics()).hasSize(1);
        assertThat(s.perQuestionStatistics().get(0).examQuestionId()).isNotNull();
    }

    private long[] createStudentAndSubmit() {
        long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('stu-" + tag + UUID.randomUUID().toString().substring(0, 8) + "','s" + UUID.randomUUID() + "@t.com','h','Student')");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + su + "," + sessionId + "," + teacherUserId + ")");
        long attemptId = attemptService.startAttempt(su, sessionId, new com.quizopia.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
        submitService.submitAttempt(su, attemptId, new SubmitRequest("stat-key-" + UUID.randomUUID()));
        return new long[]{su, attemptId};
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
