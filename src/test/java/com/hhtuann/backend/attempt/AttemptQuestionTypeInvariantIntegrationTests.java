package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.StartAttemptResponse;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Question-type ↔ source-options ↔ persisted-optionOrder invariant (R8). NOT_SUPPORTED so each
 * scenario's setup commits and the production startAttempt service runs in (and rolls back) its own
 * transaction — which makes the "0 rows after a rejected new-start" assertions observable.
 *
 * <p>Cardinality per type: NUMERIC_FILL → 0 options; SINGLE_CHOICE / MULTIPLE_CHOICE → 4–6;
 * TRUE_FALSE_MATRIX → exactly 4 (A–D). Any mismatch → 400 ATTEMPT_VALIDATION_ERROR and the whole
 * start transaction rolls back (0 attempts / 0 attempt_questions / 0 attempt_answers).
 *
 * <p>Note: V8 {@code chk_exam_options_key} caps option_key at A–F (6), so the &gt;6 cases temporarily
 * drop that CHECK within the test and restore it in a finally block.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptQuestionTypeInvariantIntegrationTests {

    private static final String[] OPTION_KEYS = {"A", "B", "C", "D", "E", "F", "G"};
    private static final String NUMERIC_KEY =
            "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";

    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager txm;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record Chain(long userId, long sessionId, long eqId) {}

    // ============================================================
    // New-start cardinality — SINGLE_CHOICE
    // ============================================================

    @Test
    void singleChoiceZeroOptionsRejectedAndRollsBack() {
        Chain c = setup("SINGLE_CHOICE", 0, false);
        AtomicReference<Exception> ex = startCapturing(c);
        assertValidationError(ex);
        assertZeroRows(c.sessionId);
    }

    @Test
    void singleChoiceThreeOptionsRejected() {
        Chain c = setup("SINGLE_CHOICE", 3, false);
        assertValidationError(startCapturing(c));
    }

    @Test
    void singleChoiceFourOptionsAccepted() {
        Chain c = setup("SINGLE_CHOICE", 4, false);
        StartAttemptResponse r = attemptService.startAttempt(c.userId, c.sessionId, new StartAttemptRequest(null));
        assertThat(r.resumed()).isFalse();
        assertThat(r.questions()).hasSize(1);
        assertThat(r.questions().get(0).questionType()).isEqualTo("SINGLE_CHOICE");
        assertThat(r.questions().get(0).options()).hasSize(4);
    }

    @Test
    void singleChoiceSevenOptionsRejected() {
        // V8 caps option_key at A–F (6); the 7th (G) needs the CHECK dropped, restored in finally.
        Chain c = setup("SINGLE_CHOICE", 0, false);
        dropKeyCheck();
        try {
            insertOptions(c.eqId, 7);
            assertValidationError(startCapturing(c));
        } finally {
            cleanup(c.sessionId, c.eqId);
            restoreKeyCheck();
        }
    }

    // ============================================================
    // New-start cardinality — MULTIPLE_CHOICE
    // ============================================================

    @Test
    void multipleChoiceThreeOptionsRejected() {
        Chain c = setup("MULTIPLE_CHOICE", 3, false);
        assertValidationError(startCapturing(c));
    }

    @Test
    void multipleChoiceFourOptionsAccepted() {
        Chain c = setup("MULTIPLE_CHOICE", 4, false);
        StartAttemptResponse r = attemptService.startAttempt(c.userId, c.sessionId, new StartAttemptRequest(null));
        assertThat(r.resumed()).isFalse();
        assertThat(r.questions().get(0).questionType()).isEqualTo("MULTIPLE_CHOICE");
        assertThat(r.questions().get(0).options()).hasSize(4);
    }

    @Test
    void multipleChoiceSevenOptionsRejected() {
        Chain c = setup("MULTIPLE_CHOICE", 0, false);
        dropKeyCheck();
        try {
            insertOptions(c.eqId, 7);
            assertValidationError(startCapturing(c));
        } finally {
            cleanup(c.sessionId, c.eqId);
            restoreKeyCheck();
        }
    }

    // ============================================================
    // New-start cardinality — TRUE_FALSE_MATRIX
    // ============================================================

    @Test
    void trueFalseMatrixThreeStatementsRejected() {
        Chain c = setup("TRUE_FALSE_MATRIX", 3, false);
        assertValidationError(startCapturing(c));
    }

    @Test
    void trueFalseMatrixFourStatementsAccepted() {
        Chain c = setup("TRUE_FALSE_MATRIX", 4, false);
        StartAttemptResponse r = attemptService.startAttempt(c.userId, c.sessionId, new StartAttemptRequest(null));
        assertThat(r.resumed()).isFalse();
        assertThat(r.questions().get(0).questionType()).isEqualTo("TRUE_FALSE_MATRIX");
        assertThat(r.questions().get(0).options()).extracting(o -> o.optionKey())
                .containsExactlyInAnyOrder("A", "B", "C", "D");
    }

    @Test
    void trueFalseMatrixFiveStatementsRejected() {
        Chain c = setup("TRUE_FALSE_MATRIX", 5, false);
        assertValidationError(startCapturing(c));
    }

    // ============================================================
    // New-start cardinality — NUMERIC_FILL
    // ============================================================

    @Test
    void numericFillWithSourceOptionRejectedAndRollsBack() {
        // NUMERIC_FILL must have 0 source options; seeding one option must abort the start and roll back.
        Chain c = setup("NUMERIC_FILL", 0, false);
        insertOptions(c.eqId, 1);
        AtomicReference<Exception> ex = startCapturing(c);
        assertValidationError(ex);
        assertZeroRows(c.sessionId);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void assertValidationError(AtomicReference<Exception> ex) {
        assertThat(ex.get()).isInstanceOf(AttemptException.class);
        assertThat(((AttemptException) ex.get()).getErrorCode().code()).isEqualTo("ATTEMPT_VALIDATION_ERROR");
    }

    private AtomicReference<Exception> startCapturing(Chain c) {
        AtomicReference<Exception> ex = new AtomicReference<>();
        try {
            attemptService.startAttempt(c.userId, c.sessionId, new StartAttemptRequest(null));
        } catch (Exception e) {
            ex.set(e);
        }
        return ex;
    }

    /** Asserts no attempt / attempt_question / attempt_answer was committed for the session. */
    private void assertZeroRows(long sessionId) {
        Integer attempts = tx().execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM attempts WHERE exam_session_id=?", Integer.class, sessionId));
        Integer aqs = tx().execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM attempt_questions aq JOIN attempts a ON a.id=aq.attempt_id WHERE a.exam_session_id=?",
                Integer.class, sessionId));
        Integer answers = tx().execute(s -> jdbc.queryForObject(
                "SELECT count(*) FROM attempt_answers aa JOIN attempts a ON a.id=aa.attempt_id WHERE a.exam_session_id=?",
                Integer.class, sessionId));
        assertThat(attempts).isZero();
        assertThat(aqs).isZero();
        assertThat(answers).isZero();
    }

    private void insertOptions(long eqId, int count) {
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                opts.append(",");
            }
            opts.append("(").append(eqId).append(",'").append(OPTION_KEYS[i]).append("','o").append(i)
                    .append("',").append(i == 2).append(",").append(i).append(")");
        }
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES " + opts);
    }

    private void dropKeyCheck() {
        jdbc.execute("ALTER TABLE exam_question_options DROP CONSTRAINT IF EXISTS chk_exam_options_key");
    }

    private void restoreKeyCheck() {
        // PostgreSQL does not support ADD CONSTRAINT IF NOT EXISTS; drop-if-exists then add is idempotent.
        // cleanup() has already deleted the >6-option rows (key 'G') so the CHECK validates cleanly.
        jdbc.execute("ALTER TABLE exam_question_options DROP CONSTRAINT IF EXISTS chk_exam_options_key");
        jdbc.execute("ALTER TABLE exam_question_options ADD CONSTRAINT chk_exam_options_key "
                + "CHECK (option_key IN ('A','B','C','D','E','F'))");
    }

    /** Builds a full chain with one exam_question of the given type and optionCount source options. */
    private Chain setup(String type, int optionCount, boolean ignored) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        long[] ids = new long[3];
        tx().executeWithoutResult(status -> {
            clock.setInstant(now);
            long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('qi" + s + "','qi" + s + "@t.com','h','QI" + s + "')");
            long studentRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + studentRoleId + ")");
            long sch = insert("INSERT INTO schools (code, name) VALUES ('QS" + s + "','Sch')");
            long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + sch + ",'GL','G')");
            long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + sch + "," + gl + ",'SUB','S')");
            long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + "," + sch + ",'TC" + s + "')");
            long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + sch + ",'SC" + s + "')");
            long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + sch + "," + subj + "," + tp + ",'B" + s + "','Bank')");
            boolean numeric = "NUMERIC_FILL".equals(type);
            String answerKey = numeric ? NUMERIC_KEY : "NULL";
            long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + s + "'," + u + ")");
            insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) "
                    + "VALUES (" + q + ",1,'" + type + "','Q',1,'{}'::jsonb," + answerKey + "," + u + ")");
            long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + sch + "," + subj + "," + tp + ",'E" + s + "','Exam')");
            long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + sch + "," + exam + ",1,'PUBLISHED',1,now()," + u + ")");
            long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
            long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, "
                    + "question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + ver + "," + sec + "," + q + ",(SELECT id FROM question_versions WHERE question_id=" + q + "),'QC','" + type + "','Q',1,0,'{}'::jsonb," + answerKey + ")");
            if (optionCount > 0) {
                insertOptions(eq, optionCount);
            }
            long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) "
                    + "VALUES (" + sch + "," + ver + "," + tp + ",'SE" + s + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + sch + "," + session + "," + sp + "," + u + ")");
            ids[0] = u;
            ids[1] = session;
            ids[2] = eq;
        });
        return new Chain(ids[0], ids[1], ids[2]);
    }

    private void cleanup(long sessionId, long eqId) {
        try { tx().executeWithoutResult(s -> jdbc.update("DELETE FROM attempts WHERE exam_session_id=?", sessionId)); } catch (Exception ignored) {}
        try { jdbc.update("DELETE FROM exam_question_options WHERE exam_question_id=?", eqId); } catch (Exception ignored) {}
    }

    private long insert(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
