package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atomic-rollback test for submit (A3.2-4): when the idempotency-cache INSERT fails AFTER the attempt
 * transition, the whole transaction rolls back — the attempt reverts to IN_PROGRESS, submitted_at and
 * submission_idempotency_key revert to null, no cache row, no grade.
 *
 * <p>Uses a test-only PostgreSQL trigger ({@code BEFORE INSERT ON idempotency_records}) that raises
 * SQLSTATE 23000 (integrity constraint violation). The service's constraint translator cannot map a
 * trigger-raised exception (no constraint name) → it rethrows the original → the service's own
 * {@code @Transactional} rolls back. The class is {@code NOT_SUPPORTED} so the service runs in its own
 * tx and the rollback is observable against committed state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptSubmitRollbackIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager txm;

    private long userId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        String s = "srb";
        userId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + s + "','" + s + "@t.com','h','" + s + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('" + s + "S','Sch')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + school + ",'TC')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + userId + ")");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + userId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
        // Install test-only trigger that fails every idempotency_records INSERT (after the attempt transition).
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_submit_cache() RETURNS TRIGGER AS $$ "
                + "BEGIN RAISE EXCEPTION USING ERRCODE = '23000', MESSAGE = 'test: submit cache insert blocked'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_submit_cache_trigger BEFORE INSERT ON idempotency_records "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_submit_cache()");
    }

    @AfterEach
    void tearDown() {
        try { jdbc.execute("DROP TRIGGER IF EXISTS test_fail_submit_cache_trigger ON idempotency_records"); } catch (Exception ignored) {}
        try { jdbc.execute("DROP FUNCTION IF EXISTS test_fail_submit_cache()"); } catch (Exception ignored) {}
        try { tx().executeWithoutResult(st -> jdbc.update("DELETE FROM attempts WHERE id=?", attemptId)); } catch (Exception ignored) {}
    }

    @Test
    void cacheInsertFailureRollsBackAttemptTransition() {
        long cacheBefore = count("idempotency_records WHERE attempt_id=" + attemptId);
        long gradesBefore = count("grades WHERE attempt_id=" + attemptId);

        AtomicReference<Exception> caught = new AtomicReference<>();
        try {
            submitService.submitAttempt(userId, attemptId, new SubmitRequest("rollback-key-001"));
        } catch (Exception e) {
            caught.set(e);
        }
        // The trigger-raised exception has no constraint name → translator rethrows (NOT AttemptException) → 500.
        assertThat(caught.get()).as("submit must fail when the cache insert is blocked").isNotNull();
        assertThat(caught.get()).isNotInstanceOf(AttemptException.class);

        // Atomic rollback against committed state.
        assertThat(status()).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT submitted_at FROM attempts WHERE id=?", Instant.class, attemptId)).isNull();
        assertThat(jdbc.queryForObject("SELECT submission_idempotency_key FROM attempts WHERE id=?", String.class, attemptId)).isNull();
        assertThat(count("idempotency_records WHERE attempt_id=" + attemptId)).isEqualTo(cacheBefore);
        assertThat(count("grades WHERE attempt_id=" + attemptId)).isEqualTo(gradesBefore);
    }

    // === helpers ===

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM attempts WHERE id=?", String.class, attemptId);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + where, Long.class);
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
