package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptQueryService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
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

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datasource-level query-count tests (A3.2-2). Wraps the real DataSource with a
 * {@link StatementCountingDataSource} via a {@link BeanPostProcessor} so every executed statement
 * (Hibernate + JdbcTemplate) is counted. Asserts the detail/my/available query plans are bounded and
 * N-independent (no N+1): exact constant counts for N=1 and N=20.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        AttemptDetailAndMyQueryCountIntegrationTests.CountingConfig.class})
@Transactional
class AttemptDetailAndMyQueryCountIntegrationTests {

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

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptQueryService queryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager em;

    private int count(Runnable call) {
        StatementCountingDataSource ds = (StatementCountingDataSource) dataSource;
        em.clear(); // empty the persistence context so entity loads issue real SELECTs (production-accurate counts)
        ds.reset();
        call.run();
        return ds.count();
    }

    // === DETAIL: N=1 vs N=20 (constant; no per-question query) ===

    @Test
    void detailQueryCountIsConstantAcrossN() {
        long u1 = newStudentWithDetailAttempt(1);
        long u20 = newStudentWithDetailAttempt(20);
        long a1 = activeAttemptOf(u1);
        long a20 = activeAttemptOf(u20);
        int c1 = count(() -> queryService.getAttemptDetail(u1, a1));
        int c20 = count(() -> queryService.getAttemptDetail(u20, a20));
        // Constant regardless of question count: auth(3) + attempt findById(1) + joined questions(1) + options batch(1) + answers batch(1) = 7.
        assertThat(c1).isEqualTo(c20).isEqualTo(7);
    }

    // === MY: N=1 vs N=20 (constant; no per-attempt session query) ===

    @Test
    void myQueryCountIsConstantAcrossN() {
        long u1 = newStudentWithMyAttempts(1);
        long u20 = newStudentWithMyAttempts(20);
        int c1 = count(() -> queryService.getMyAttempts(u1, 0, 100));
        int c20 = count(() -> queryService.getMyAttempts(u20, 0, 100));
        // Constant regardless of attempt count: auth(3) + page(1) + count(1) = 5.
        assertThat(c1).isEqualTo(c20).isEqualTo(5);
    }

    // === AVAILABLE regression: N=1 = N=20 = 4 (tightens the A3.2-1 comment-only assertion) ===

    @Test
    void availableQueryCountIsConstantFour() {
        long u1 = newStudentWithAvailableSessions(1);
        long u4 = newStudentWithAvailableSessions(4);
        long u20 = newStudentWithAvailableSessions(20);
        // 3 auth queries (role + permission + profile) + 1 native available query = 4 constant.
        assertThat(count(() -> attemptService.getAvailableSessions(u1))).isEqualTo(4);
        assertThat(count(() -> attemptService.getAvailableSessions(u4))).isEqualTo(4);
        assertThat(count(() -> attemptService.getAvailableSessions(u20))).isEqualTo(4);
    }

    // ============================================================
    // Fixtures
    // ============================================================

    /** Creates a student + version with N SINGLE_CHOICE questions (4 opts) + an IN_PROGRESS attempt; returns userId. */
    private long newStudentWithDetailAttempt(int n) {
        long[] chain = baseChain("D" + n);
        long ver = chain[2];
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long bank = chain[3];
        long user = chain[0];
        for (int i = 0; i < n; i++) {
            long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + i + "'," + user + ")");
            long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + user + ")");
            long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1," + i + ",'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        }
        long session = openSession(chain, ver);
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + chain[1] + "," + session + "," + chain[4] + "," + user + ")");
        attemptService.startAttempt(user, session, new StartAttemptRequest(null));
        return user;
    }

    /** Creates a student with N SUBMITTED attempts; returns userId. */
    private long newStudentWithMyAttempts(int n) {
        long[] chain = baseChain("M" + n);
        long ver = chain[2];
        long session = openSession(chain, ver);
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + chain[1] + "," + session + "," + chain[4] + "," + chain[0] + ")");
        long user = chain[0];
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, "
                    + "status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES (" + chain[1] + "," + session
                    + "," + chain[4] + "," + ver + "," + (i + 1) + ",'SUBMITTED', now()-interval '2 hour', now()-interval '1 hour', now()-interval '1 hour','K" + i + "')");
        }
        return user;
    }

    /** Creates a student who is an ELIGIBLE participant of N OPEN sessions; returns userId. */
    private long newStudentWithAvailableSessions(int n) {
        long[] chain = baseChain("A" + n);
        long ver = chain[2];
        long user = chain[0];
        for (int i = 0; i < n; i++) {
            long session = openSession(chain, ver);
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + chain[1] + "," + session + "," + chain[4] + "," + user + ")");
        }
        return user;
    }

    /** baseChain → [userId, schoolId, examVersionId, bankId, studentProfileId, teacherProfileId]. */
    private long[] baseChain(String tag) {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        long user = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + tag + "','" + tag + "@t.com','h','" + tag + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + user + "," + roleId + ")");
        long school = insert("INSERT INTO schools (code, name) VALUES ('" + tag + "S','Sch')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long teacher = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + user + "," + school + ",'TC" + tag + "')");
        long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + user + "," + school + ",'SC" + tag + "')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + teacher + ",'B" + tag + "','Bank')");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + teacher + ",'E" + tag + "','E')");
        long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + user + ")");
        return new long[]{user, school, ver, bank, sp, teacher};
    }

    private long openSession(long[] chain, long ver) {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        return insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + chain[1] + "," + ver + "," + chain[5] + ",'S" + System.nanoTime() + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + chain[0] + ",'" + now.minusSeconds(3600) + "')");
    }

    private long activeAttemptOf(long userId) {
        return jdbc.queryForObject("SELECT a.id FROM attempts a JOIN student_profiles sp ON sp.id=a.student_profile_id WHERE sp.user_id=" + userId
                + " AND a.status='IN_PROGRESS' ORDER BY a.id DESC LIMIT 1", Long.class);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
