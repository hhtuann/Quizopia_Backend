package com.quizopia.backend.attempt.application;

import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link AttemptService#classifyAndTranslate(Throwable)} end-to-end against a real
 * PostgreSQL, using a test-only trigger that raises a named constraint violation via
 * {@code RAISE EXCEPTION USING CONSTRAINT = ...} (read through {@code PSQLException#getServerErrorMessage().getConstraint()}).
 *
 * <p>A — duplicate constraint → 409 {@code ATTEMPT_DUPLICATE_START}.
 * <br>B — snapshot allowlist constraint → 400 {@code ATTEMPT_VALIDATION_ERROR}.
 * <br>C — unknown constraint → 500 generic, body carries no constraint name / SQL / class / stack trace.
 * <br>D — a raw {@code PSQLException} thrown by JdbcTemplate (no Hibernate wrapper in the chain)
 *         is still translated, proving the reflective PSQL fallback path is exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptConstraintTranslationIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private long studentUserId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ct','ct@t.com','h','CT')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + java.util.UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + studentUserId + "," + subj + ",'B','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + studentUserId + ")");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + studentUserId + "," + subj + ",'E','Exam','PUBLISHED',now(),1)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','A',false,0),(" + eq + ",'B','B',true,1)");
        // max_attempts=5 so the service reaches the attempt INSERT (where the test trigger fires).
        sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'S','Sess','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + studentUserId + ",'" + now.minusSeconds(3600) + "',60)");
        insert("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + sessionId + "," + studentUserId + ")");
        installTestConstraintTrigger();
    }

    // A. Duplicate constraint → 409 ATTEMPT_DUPLICATE_START.
    @Test
    void duplicateConstraintReturns409() throws Exception {
        armTrigger("uk_attempts_one_active_per_session_user");
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId))))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_DUPLICATE_START"));
    }

    // B. Snapshot allowlist constraint → 400 ATTEMPT_VALIDATION_ERROR.
    @Test
    void snapshotConstraintReturns400() throws Exception {
        armTrigger("chk_attempt_questions_type");
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId))))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTEMPT_VALIDATION_ERROR"));
    }

    // C. Unknown constraint → 500 generic; body must not leak internals.
    @Test
    void unknownConstraintReturns500WithoutLeak() throws Exception {
        armTrigger("uk_test_unknown_xyz");
        String body = mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId))))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        // Never mapped to an attempt validation error, and no internal detail leaks.
        assertThat(body).doesNotContain("ATTEMPT_VALIDATION_ERROR")
                .doesNotContain("uk_test_unknown_xyz")
                .doesNotContain("constraint")
                .doesNotContain("SQL")
                .doesNotContain("org.hibernate")
                .doesNotContain("org.postgresql")
                .doesNotContain("at com.")
                .doesNotContain("Caused by");
    }

    // D. Raw PSQLException (JdbcTemplate path, no Hibernate wrapper) is still translated
    //    via the reflective PSQLException#getServerErrorMessage().getConstraint() fallback.
    @Test
    void psqlFallbackExtractsConstraintName() {
        // Trigger must be inert for the seed inserts below.
        armTrigger(null);
        long attemptId = insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, status, started_at, deadline_at) SELECT s.id, s.created_by, "
                + "1, 'IN_PROGRESS', '" + clock.instant() + "', '" + clock.instant().plusSeconds(3600) + "' "
                + "FROM exam_sessions s WHERE s.id = " + sessionId);
        long eqId = jdbc.queryForObject("SELECT id FROM exam_questions WHERE exam_id=(SELECT exam_id FROM exam_sessions WHERE id=" + sessionId + ")", Long.class);
        jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, default_points, display_order) "
                + "VALUES (" + attemptId + "," + eqId + ",'SINGLE_CHOICE',1,0)");

        DataIntegrityViolationException caught = null;
        try {
            // Duplicate (attempt_id, exam_question_id) → violates uk_attempt_questions_attempt_exam.
            jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, default_points, display_order) "
                    + "VALUES (" + attemptId + "," + eqId + ",'SINGLE_CHOICE',1,1)");
        } catch (DataIntegrityViolationException e) {
            caught = e;
        }
        assertThat(caught).isNotNull();
        // The constraint name is extracted — and since JdbcTemplate's chain has NO Hibernate
        // ConstraintViolationException, only the PSQLException fallback could resolve it.
        assertThat(AttemptService.extractConstraintName(caught)).isEqualTo("uk_attempt_questions_attempt_exam");
        assertThat(hasHibernateConstraintViolation(caught)).isFalse();
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** True if the Hibernate ConstraintViolationException is anywhere in the cause chain. */
    private static boolean hasHibernateConstraintViolation(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** Installs a control-table + function + BEFORE INSERT trigger on attempts; inert until armed. */
    private void installTestConstraintTrigger() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_constraint_ctrl (id int PRIMARY KEY, cname text)");
        jdbc.update("INSERT INTO test_constraint_ctrl (id, cname) VALUES (1, NULL) ON CONFLICT (id) DO NOTHING");
        jdbc.update("UPDATE test_constraint_ctrl SET cname = NULL WHERE id = 1");
        jdbc.execute("CREATE OR REPLACE FUNCTION test_translate_constraint() RETURNS TRIGGER AS $$ "
                + "DECLARE c text; BEGIN "
                + "SELECT cname INTO c FROM test_constraint_ctrl WHERE id = 1; "
                + "IF c IS NOT NULL THEN "
                + "RAISE EXCEPTION USING ERRCODE = '23505', CONSTRAINT = c, MESSAGE = 'test constraint translation'; "
                + "END IF; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS test_translate_constraint_trg ON attempts");
        jdbc.execute("CREATE TRIGGER test_translate_constraint_trg BEFORE INSERT ON attempts "
                + "FOR EACH ROW EXECUTE FUNCTION test_translate_constraint()");
    }

    /** Arms the test trigger to raise a constraint violation with the given name (null = inert). */
    private void armTrigger(String constraintName) {
        jdbc.update("UPDATE test_constraint_ctrl SET cname = " + (constraintName == null ? "NULL" : "'" + constraintName + "'") + " WHERE id = 1");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
