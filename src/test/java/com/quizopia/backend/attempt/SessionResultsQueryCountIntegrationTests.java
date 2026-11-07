package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.SessionResultService;
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
 * Day 8 R4 datasource-level query-count evidence for session results (FINDING 1B).
 *
 * <p>Wraps the real DataSource with {@link StatementCountingDataSource} and asserts the paginated
 * session-results read uses a CONSTANT number of statements regardless of student count (no N+1):
 * the attempt-count is delivered by {@code COUNT(*) OVER} inside the same CTE, so there is no
 * per-student count query. N=1 and N=50 must issue the same statement count.
 *
 * <p>Fixtures insert attempts + grades directly via JDBC (the read path is what is measured; the
 * grading pipeline is exercised elsewhere).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class,
        SessionResultsQueryCountIntegrationTests.CountingConfig.class})
@Transactional
class SessionResultsQueryCountIntegrationTests {

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

    @Autowired private SessionResultService sessionResultService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager em;

    /** In-memory teacher→session map (avoids needing a marker table; migrations V1–V9 are frozen). */
    private final java.util.Map<Long, Long> teacherSession = new java.util.HashMap<>();

    private int count(Runnable call) {
        StatementCountingDataSource ds = (StatementCountingDataSource) dataSource;
        em.clear();
        ds.reset();
        call.run();
        return ds.count();
    }

    @Test
    void sessionResultsQueryCountConstantOneVsFifty() {
        long teacherA = buildSessionWithNStudents("A", 1);
        long teacherB = buildSessionWithNStudents("B", 50);

        int c1 = count(() -> sessionResultService.getSessionResults(teacherA,
                sessionOf(teacherA), 0, 100, null, null, null, null, null));
        int c50 = count(() -> sessionResultService.getSessionResults(teacherB,
                sessionOf(teacherB), 0, 100, null, null, null, null, null));

        // 3 statements constant: (1) creator authorization check (user-centric ownership),
        // (2) CTE count query, (3) CTE page query with LIMIT/OFFSET.
        // COUNT(*) OVER delivers attempt_count inside the page query — NO per-student count query.
        assertThat(c1)
                .as("N=1 statement count must equal N=50 (no N+1)")
                .isEqualTo(c50)
                .isEqualTo(3);
    }

    @Test
    void sessionResultsCountQueryDoesNotScaleWithStudentCount() {
        long teacher10 = buildSessionWithNStudents("C", 10);
        long teacher40 = buildSessionWithNStudents("D", 40);
        int c10 = count(() -> sessionResultService.getSessionResults(teacher10,
                sessionOf(teacher10), 0, 100, null, null, null, null, null));
        int c40 = count(() -> sessionResultService.getSessionResults(teacher40,
                sessionOf(teacher40), 0, 100, null, null, null, null, null));
        assertThat(c10).isEqualTo(c40);
    }

    @Test
    void pageSizeCapsRowsAtTen() {
        long teacher = buildSessionWithNStudents("E", 25);
        var page = sessionResultService.getSessionResults(teacher,
                sessionOf(teacher), 0, 10, null, null, null, null, null);
        assertThat(page.items()).hasSizeLessThanOrEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(25);
    }

    /** Builds an OPEN session owned by a fresh teacher, with N students each having one SUBMITTED attempt + grade. Returns teacher userId. */
    private long buildSessionWithNStudents(String tag, int n) {
        Instant now = Instant.parse("2026-07-06T08:00:00Z");
        clock.setInstant(now);
        long teacher = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('t" + tag + n + "','t" + tag + n + "@t.com','h','T')");
        long tr = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + teacher + "," + tr + ")");
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + java.util.UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','M')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacher + "," + subj + ",'B','B')");
        long exam = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacher + "," + subj + ",'E','E','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacher + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacher + ")");
        jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        long sr = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        long session = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES (" + exam + ",'S" + tag + n + "','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + teacher + ",'" + now.minusSeconds(3600) + "',60)");
        teacherSession.put(teacher, session);
        for (int i = 0; i < n; i++) {
            long su = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('su" + tag + n + "_" + i + "','" + tag + n + "_" + i + "@t.com','h','S')");
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + sr + ")");
            jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + su + "," + session + "," + teacher + ")");
            long attempt = ins("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES (" + session + "," + su + ",1,'SUBMITTED','" + now.minusSeconds(1800) + "','" + now.plusSeconds(1800) + "','" + now.minusSeconds(60) + "','K" + tag + n + "_" + i + "')");
            jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, percentage, status, graded_at) VALUES (" + attempt + ",0,0,1,0.0000,'AUTO_GRADED','" + now.minusSeconds(60) + "')");
        }
        return teacher;
    }

    private long sessionOf(long teacherUserId) {
        return teacherSession.get(teacherUserId);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
