package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptResultService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.AttemptResultResponse;
import com.hhtuann.backend.attempt.dto.QuestionResultView;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 8 Gate C core — student own attempt result + BEST result. Reads persisted grading snapshot only
 * (no re-grade, no answer key). Verifies score/maxScore/percentage/isBest/attemptCount/per-question.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptResultIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private AttemptResultService resultService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long studentUserId;
    private long sessionId;
    private long attemptId;
    private long spId;
    private static final String KEY = "result-key-001";

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-06T08:00:00Z"));
        studentUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sr','sr@t.com','h','SR')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('RS','Result School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + school + ",'TC')");
        spId = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + studentUserId + "),'B','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + studentUserId + "),'E','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + studentUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + studentUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        sessionId = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + studentUserId + "),'S1','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + spId + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, sessionId, new com.hhtuann.backend.attempt.dto.StartAttemptRequest(null)).attemptId();
        em.flush(); em.clear();
    }

    @Test
    void ownResultHasGradingSummaryNoAnswerKey() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest(KEY));
        em.flush(); em.clear();
        AttemptResultResponse r = resultService.getAttemptResult(studentUserId, "STUDENT", attemptId);
        assertThat(r.attemptId()).isEqualTo(attemptId);
        assertThat(r.examSessionId()).isEqualTo(sessionId);
        assertThat(r.status()).isEqualTo("SUBMITTED");
        assertThat(r.submittedAt()).isNotNull();
        assertThat(r.gradedAt()).isNotNull();
        assertThat(r.gradeStatus()).isEqualTo("AUTO_GRADED");
        assertThat(r.score()).isEqualByComparingTo("0"); // unanswered → 0
        assertThat(r.maxScore()).isEqualByComparingTo("1");
        assertThat(r.percentage()).isEqualByComparingTo("0.00");
        assertThat(r.isBest()).isTrue();
        assertThat(r.attemptCount()).isEqualTo(1);
        assertThat(r.questionResults()).hasSize(1);
        QuestionResultView qr = r.questionResults().get(0);
        assertThat(qr.correct()).isFalse();
        assertThat(qr.answered()).isFalse();
        assertThat(qr.awardedScore()).isEqualByComparingTo("0");
        assertThat(qr.maxScore()).isEqualByComparingTo("1");
        assertThat(qr.questionType()).isEqualTo("SINGLE_CHOICE");
        // No answer key in the DTO (structural)
        assertThat(r.toString()).doesNotContain("answerKey").doesNotContain("expectedAnswer")
                .doesNotContain("isCorrect").doesNotContain("correctOption");
    }

    @Test
    void bestResultReturnsTheSameSingleAttempt() {
        submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest(KEY));
        em.flush(); em.clear();
        AttemptResultResponse best = resultService.getMyBestResult(studentUserId, sessionId);
        assertThat(best.attemptId()).isEqualTo(attemptId);
        assertThat(best.isBest()).isTrue();
    }

    @Test
    void unsubmittedAttemptReturnsNotSubmittedError() {
        // attempt is IN_PROGRESS (not submitted)
        try {
            resultService.getAttemptResult(studentUserId, "STUDENT", attemptId);
            assertThat(false).as("should throw ATTEMPT_NOT_SUBMITTED").isTrue();
        } catch (com.hhtuann.backend.attempt.exception.AttemptException e) {
            assertThat(e.getErrorCode()).isEqualTo(com.hhtuann.backend.attempt.exception.AttemptErrorCode.ATTEMPT_NOT_SUBMITTED);
        }
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
