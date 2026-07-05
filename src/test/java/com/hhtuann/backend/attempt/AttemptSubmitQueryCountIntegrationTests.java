package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
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

import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datasource-level query-count tests for submit (A3.2-4). Wraps the real DataSource with
 * {@link StatementCountingDataSource}; fixtures are built outside the measured window; the persistence
 * context is cleared and the counter reset immediately before each measured production call. Asserts
 * bounded, N-independent counts (no N+1) for first submit, same-key cached retry, different-key
 * already-submitted, and cross-attempt existing-key conflict.
 *
 * <p>Measured plan: auth(3: role+permission+profile) + attempt FOR UPDATE(1). First submit adds a
 * precheck lookup(1) + flush{attempt UPDATE(1) + cache INSERT(1)}. Cached retry adds a cache read(1).
 * Cross-attempt conflict adds the precheck lookup(1). None load the N questions/answers.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        AttemptSubmitQueryCountIntegrationTests.CountingConfig.class})
@Transactional
class AttemptSubmitQueryCountIntegrationTests {

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

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager em;

    private int count(Runnable call) {
        StatementCountingDataSource ds = (StatementCountingDataSource) dataSource;
        em.clear();
        ds.reset();
        try {
            call.run();
        } catch (RuntimeException expected) {
            // Different-key (ALREADY_SUBMITTED) and cross-attempt (IDEMPOTENCY_CONFLICT) paths throw
            // before returning; the statement count up to the throw is what we measure.
        }
        return ds.count();
    }

    // A. first submit: Day 8 grades atomically — reads are batched (N-independent), writes scale with N
    //    (1 Grade + N GradeItems; IDENTITY generation = immediate INSERT per item). The total is NOT
    //    constant (unlike Day 7's no-grade submit); it grows linearly with the question count.
    @Test
    void firstSubmitQueryCountGrowsLinearly() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = inProgressAttemptOf(u1);
        long a20 = inProgressAttemptOf(u20);
        int c1 = count(() -> submitService.submitAttempt(u1, a1, new SubmitRequest("first-1")));
        int c20 = count(() -> submitService.submitAttempt(u20, a20, new SubmitRequest("first-20")));
        // Writes scale with question count (N grade_items); the growth is linear and bounded per question.
        int writeDelta = c20 - c1;
        assertThat(writeDelta).as("Day 8 submit writes scale with question count").isPositive();
        int perQuestion = writeDelta / (20 - 1);
        assertThat(perQuestion).as("at most a few statements per extra question (grade_item INSERT)")
                .isBetween(1, 4);
    }

    // B. same-key cached retry: N=1 == N=20; no UPDATE/INSERT.
    @Test
    void cachedRetryQueryCountIsConstant() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = inProgressAttemptOf(u1);
        long a20 = inProgressAttemptOf(u20);
        markSubmitted(u1, a1, "cached");
        markSubmitted(u20, a20, "cached");
        int c1 = count(() -> submitService.submitAttempt(u1, a1, new SubmitRequest("cached")));
        int c20 = count(() -> submitService.submitAttempt(u20, a20, new SubmitRequest("cached")));
        assertThat(c1).as("cached-retry N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(5);
    }

    // C. different-key already-submitted: N=1 == N=20.
    @Test
    void differentKeyQueryCountIsConstant() {
        long u1 = newStudent(1);
        long u20 = newStudent(20);
        long a1 = inProgressAttemptOf(u1);
        long a20 = inProgressAttemptOf(u20);
        markSubmitted(u1, a1, "original");
        markSubmitted(u20, a20, "original");
        int c1 = count(() -> submitService.submitAttempt(u1, a1, new SubmitRequest("other")));
        int c20 = count(() -> submitService.submitAttempt(u20, a20, new SubmitRequest("other")));
        assertThat(c1).as("different-key N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(4);
    }

    // D. cross-attempt existing-key conflict: bounded constant (no N+1).
    @Test
    void crossAttemptConflictQueryCountIsConstant() {
        long u1a = newStudent(1);
        long u20a = newStudent(20);
        long a1first = inProgressAttemptOf(u1a);
        long a20first = inProgressAttemptOf(u20a);
        markSubmitted(u1a, a1first, "shared");
        markSubmitted(u20a, a20first, "shared");
        long a1second = secondAttemptOf(u1a);
        long a20second = secondAttemptOf(u20a);
        int c1 = count(() -> submitService.submitAttempt(u1a, a1second, new SubmitRequest("shared")));
        int c20 = count(() -> submitService.submitAttempt(u20a, a20second, new SubmitRequest("shared")));
        assertThat(c1).as("cross-attempt conflict N=1").isEqualTo(c20).as("must be N-independent").isEqualTo(5);
    }

    // ============================================================
    // Fixtures
    // ============================================================

    /** Creates a student + version with N SINGLE_CHOICE questions (4 opts) + one IN_PROGRESS attempt. */
    private long newStudent(int n) {
        String tag = "SQ" + n + "_" + System.nanoTime();
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

    private long inProgressAttemptOf(long userId) {
        return jdbc.queryForObject("SELECT a.id FROM attempts a JOIN student_profiles sp ON sp.id=a.student_profile_id WHERE sp.user_id=" + userId
                + " AND a.status='IN_PROGRESS' ORDER BY a.id DESC LIMIT 1", Long.class);
    }

    private long secondAttemptOf(long userId) {
        // Start a second attempt (the first is SUBMITTED, so one-active is satisfied).
        long session = jdbc.queryForObject("SELECT a.exam_session_id FROM attempts a JOIN student_profiles sp ON sp.id=a.student_profile_id WHERE sp.user_id=" + userId + " LIMIT 1", Long.class);
        return attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
    }

    /** Marks the attempt SUBMITTED + inserts a well-formed cache row (committed-state setup, outside the measured window). */
    private void markSubmitted(long userId, long attemptId, String key) {
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=?, submission_idempotency_key=?, updated_at=? WHERE id=?",
                java.sql.Timestamp.from(now), key, java.sql.Timestamp.from(now), attemptId);
        Integer attemptNumber = jdbc.queryForObject("SELECT attempt_number FROM attempts WHERE id=?", Integer.class, attemptId);
        String body = "'{\"attemptId\":" + attemptId + ",\"status\":\"SUBMITTED\","
                + "\"submittedAt\":\"" + now.toString() + "\",\"serverTime\":\"" + now.toString() + "\","
                + "\"attemptNumber\":" + attemptNumber + "}'::jsonb";
        jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, idempotency_key, response_status, response_body, expires_at) VALUES ("
                + userId + "," + attemptId + ",'ATTEMPT_SUBMIT','" + key + "',200," + body + ",NULL)");
        em.clear();
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
