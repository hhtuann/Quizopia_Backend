package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partial-snapshot rollback test: proves that when a child insert fails mid-snapshot,
 * the entire transaction rolls back (0 attempt, 0 attempt_question, 0 attempt_answer).
 * Uses a test-only PostgreSQL trigger that fails on the 2nd attempt_questions insert
 * with SQLSTATE 23000 (integrity constraint violation) so Spring maps it to
 * DataIntegrityViolationException (caught by the service).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptSnapshotRollbackIntegrationTests {

    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long studentUserId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        String s = "rb";
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + s + "','" + s + "@t.com','h','" + s + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long school = insert("INSERT INTO schools (code, name) VALUES ('" + s + "S','Sch')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + school + ",'TC')");
        long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
        // Two distinct source questions (V8 uk_exam_questions_version_source requires unique source per version).
        long q1 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q1'," + studentUserId + ")");
        long qv1 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q1 + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + studentUserId + ")");
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q2'," + studentUserId + ")");
        long qv2 = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','Q2',1,'{}'::jsonb," + studentUserId + ")");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','Exam')");
        long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',2,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        // First exam_question: valid (4 source options so the SINGLE_CHOICE cardinality invariant holds
        // and the snapshot reaches the 2nd attempt_questions INSERT where the test trigger fires).
        long eq1 = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q1 + "," + qv1 + ",'QC1','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
        // Second exam_question: valid source data, but the test trigger below will fail on the 2nd attempt_questions INSERT.
        long eq2 = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q2 + "," + qv2 + ",'QC2','SINGLE_CHOICE','Q2',1,1,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq1 + ",'A','a',false,0),(" + eq1 + ",'B','b',false,1),(" + eq1 + ",'C','c',true,2),(" + eq1 + ",'D','d',false,3),"
                + "(" + eq2 + ",'A','a',false,0),(" + eq2 + ",'B','b',false,1),(" + eq2 + ",'C','c',true,2),(" + eq2 + ",'D','d',false,3)");
        sessionId = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',1," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + sessionId + "," + sp + "," + studentUserId + ")");
        // Install test-only trigger: fail on 2nd attempt_questions INSERT per attempt.
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_2nd_aq() RETURNS TRIGGER AS $$ "
                + "DECLARE cnt INTEGER; BEGIN "
                + "SELECT count(*) INTO cnt FROM attempt_questions WHERE attempt_id = NEW.attempt_id; "
                + "IF cnt >= 1 THEN RAISE EXCEPTION USING ERRCODE = '23000', MESSAGE = 'test: second AQ not allowed'; END IF; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_2nd_aq_trigger BEFORE INSERT ON attempt_questions "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_2nd_aq()");
    }

    @AfterEach
    void tearDown() {
        try { jdbc.execute("DROP TRIGGER IF EXISTS test_fail_2nd_aq_trigger ON attempt_questions"); } catch (Exception ignored) {}
        try { jdbc.execute("DROP FUNCTION IF EXISTS test_fail_2nd_aq()"); } catch (Exception ignored) {}
        try { jdbc.update("DELETE FROM attempts WHERE exam_session_id=?", sessionId); } catch (Exception ignored) {}
    }

    @Test
    void partialSnapshotRollbackLeavesZeroRows() {
        // The trigger fails on the 2nd AttemptQuestion INSERT (SQLSTATE 23000 → DataIntegrityViolationException).
        // The service catches it and throws AttemptException(VALIDATION_ERROR).
        // The service's own @Transactional rolls back: 0 attempt, 0 attempt_question, 0 attempt_answer.
        AtomicReference<Exception> caught = new AtomicReference<>();
        try { attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null)); }
        catch (Exception e) { caught.set(e); }
        assertThat(caught.get()).isNotNull(); // service rethrows unknown constraint — correct behavior

        // Verify: 0 attempts, 0 attempt_questions, 0 attempt_answers.
        Integer attempts = jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=?", Integer.class, sessionId);
        Integer aqs = jdbc.queryForObject("SELECT count(*) FROM attempt_questions aq JOIN attempts a ON a.id=aq.attempt_id WHERE a.exam_session_id=?", Integer.class, sessionId);
        Integer answers = jdbc.queryForObject("SELECT count(*) FROM attempt_answers aa JOIN attempts a ON a.id=aa.attempt_id WHERE a.exam_session_id=?", Integer.class, sessionId);
        assertThat(attempts).isZero();
        assertThat(aqs).isZero();
        assertThat(answers).isZero();
    }

    private long insert(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
