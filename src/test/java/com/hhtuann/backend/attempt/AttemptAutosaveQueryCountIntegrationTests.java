package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptAutosaveService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datasource-level query-count tests for autosave (A3.2-3R2 §8). Wraps the real DataSource with a
 * {@link StatementCountingDataSource} so every executed statement (Hibernate + JdbcTemplate) is counted.
 * Asserts the autosave plan is bounded and N-independent (no N+1): exact constant counts for N=1 and N=20
 * across accepted-insert, accepted-update, and stale.
 *
 * <p>Measured plan (constant): auth(3: role + permission + profile) + attempt FOR UPDATE(1) +
 * attempt-question lookup(1) + UPSERT(1) + answer-row read(1) + last_saved_at UPDATE on accepted(1) = 8
 * logical statements, PLUS 1 Hibernate auto-flush that re-persists the loaded AttemptQuestion snapshot
 * before the {@code @Modifying} UPSERT (a JsonNode dirty-checking quirk; no value change, no correctness
 * impact). Measured: accepted insert/update = 9; stale = 8 (no last_saved_at UPDATE). All N-independent.
 *
 * <p>Fixture setup is OUTSIDE the measured window; the persistence context is cleared and the counter
 * reset immediately before the measured production call.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        AttemptAutosaveQueryCountIntegrationTests.CountingConfig.class})
@Transactional
class AttemptAutosaveQueryCountIntegrationTests {

    @TestConfiguration
    static class CountingConfig {
        @Bean
        static BeanPostProcessor countingDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource ds && !(bean instanceof StatementCountingDataSource)) {
                        return new StatementCountingDataSource(ds, new AtomicInteger());
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager em;

    private int count(Runnable call) {
        StatementCountingDataSource ds = (StatementCountingDataSource) dataSource;
        em.clear(); // detach loaded entities so the measured call issues real SELECTs (production-accurate)
        ds.reset();
        call.run();
        return ds.count();
    }

    // A. accepted first insert: N=1 == N=20 == 8.
    @Test
    void acceptedInsertQueryCountIsConstant() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = attemptOf(u1);
        long a20 = attemptOf(u20);
        int c1 = count(() -> autosaveService.saveAnswer(u1, a1, req(firstAq(a1), "A", 1)));
        int c20 = count(() -> autosaveService.saveAnswer(u20, a20, req(firstAq(a20), "A", 1)));
        assertThat(c1).as("accepted-insert N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(9);
    }

    // B. accepted higher-sequence update: N=1 == N=20 == 9 (same plan; UPSERT UPDATE instead of INSERT).
    @Test
    void acceptedUpdateQueryCountIsConstant() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = attemptOf(u1);
        long a20 = attemptOf(u20);
        // Prior accepted save (outside the measured window) so the measured call takes the UPDATE path.
        autosaveService.saveAnswer(u1, a1, req(firstAq(a1), "A", 1));
        autosaveService.saveAnswer(u20, a20, req(firstAq(a20), "A", 1));
        int c1 = count(() -> autosaveService.saveAnswer(u1, a1, req(firstAq(a1), "B", 5)));
        int c20 = count(() -> autosaveService.saveAnswer(u20, a20, req(firstAq(a20), "B", 5)));
        assertThat(c1).as("accepted-update N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(9);
    }

    // C. stale equal/lower: N=1 == N=20 == 8 (no last_saved_at UPDATE on stale).
    @Test
    void staleQueryCountIsConstant() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = attemptOf(u1);
        long a20 = attemptOf(u20);
        autosaveService.saveAnswer(u1, a1, req(firstAq(a1), "A", 5));
        autosaveService.saveAnswer(u20, a20, req(firstAq(a20), "A", 5));
        int c1 = count(() -> autosaveService.saveAnswer(u1, a1, req(firstAq(a1), "B", 3)));
        int c20 = count(() -> autosaveService.saveAnswer(u20, a20, req(firstAq(a20), "B", 3)));
        assertThat(c1).as("stale N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(8);
        assertThat(c1).as("stale must be one fewer than accepted (no last_saved_at UPDATE)").isEqualTo(9 - 1);
    }

    // ============================================================
    // Fixtures
    // ============================================================

    /** Creates a student + version with N SINGLE_CHOICE questions (4 opts) + an IN_PROGRESS attempt. */
    private long newStudent(int n) {
        String tag = "QCN" + n + "_" + System.nanoTime();
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        long user = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + tag + "','" + tag + "@t.com','h','" + tag + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + user + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('" + tag + "S','Sch')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + user + "," + school + ",'TC" + tag + "')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + user + "," + school + ",'SC" + tag + "')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B" + tag + "','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E" + tag + "','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED'," + n + ",now()," + user + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        for (int i = 0; i < n; i++) {
            long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + i + tag + "'," + user + ")");
            long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + user + ")");
            long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1," + i + ",'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        }
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + "," + tp + ",'S" + tag + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + user + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + user + ")");
        attemptService.startAttempt(user, session, new StartAttemptRequest(null));
        return user;
    }

    private long attemptOf(long userId) {
        return jdbc.queryForObject("SELECT a.id FROM attempts a JOIN student_profiles sp ON sp.id=a.student_profile_id WHERE sp.user_id=" + userId
                + " AND a.status='IN_PROGRESS' ORDER BY a.id DESC LIMIT 1", Long.class);
    }

    private long firstAq(long attemptId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " ORDER BY id LIMIT 1", Long.class);
    }

    private SaveAnswerRequest req(long aqId, String key, long seq) {
        return new SaveAnswerRequest(aqId, null, single(key), seq, null);
    }

    private JsonNode single(String key) {
        return objectMapper.createObjectNode().put("selectedOptionKey", key);
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
