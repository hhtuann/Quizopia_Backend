package com.hhtuann.backend.exam;

import com.hhtuann.backend.exam.application.ExamService;
import com.hhtuann.backend.exam.application.ExamSessionService;
import com.hhtuann.backend.exam.dto.CreateExamRequest;
import com.hhtuann.backend.exam.dto.CreateExamSessionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.hhtuann.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the lazy-close bulk UPDATE in listMySessions (F2 evidence).
 * Two concurrent list calls must both succeed — no OptimisticLockException, no 500 —
 * and the session must end up CLOSED with non-null closedAt.
 *
 * <p>Non-transactional + {@code @DirtiesContext(AFTER_CLASS)} so each listMySessions runs
 * in its own committed transaction (separate persistence contexts) and the committed data
 * doesn't pollute the shared DB.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExamSessionListConcurrencyIntegrationTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExamService examService;
    @Autowired private ExamSessionService sessionService;

    private long teacherUserId;
    private long expiredSessionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('lc','lc@t','h','LC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        long schoolId = insert("INSERT INTO schools (code, name) VALUES ('LCC','LC School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'LCC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'LCB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "LCE", "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(1, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        // Create expired OPEN session (past window)
        Instant pastStarts = Instant.now().minusSeconds(7200);
        Instant pastEnds = Instant.now().minusSeconds(3600);
        expiredSessionId = sessionService.createSession(teacherUserId,
                new CreateExamSessionRequest(examId, 1, "EXP", "Expired", pastStarts, pastEnds, 1)).id();
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", expiredSessionId);
    }

    @Test
    void concurrentListLazyCloseNoOptimisticLockException() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await();
            try {
                sessionService.listMySessions(teacherUserId, null, null, null, 0, 20, null);
                return "OK";
            } catch (Exception e) {
                return e.getClass().getSimpleName();
            }
        };
        Future<String> f1 = pool.submit(task);
        Future<String> f2 = pool.submit(task);
        start.countDown();
        String r1 = f1.get(30, TimeUnit.SECONDS);
        String r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Both succeed — no OptimisticLockException, no 500.
        assertThat(List.of(r1, r2)).containsOnly("OK");

        // Session is CLOSED with non-null closedAt.
        String status = jdbc.queryForObject("SELECT status FROM exam_sessions WHERE id=?", String.class, expiredSessionId);
        assertThat(status).isEqualTo("CLOSED");
        Object closedAt = jdbc.queryForObject("SELECT closed_at FROM exam_sessions WHERE id=?", Object.class, expiredSessionId);
        assertThat(closedAt).isNotNull();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
