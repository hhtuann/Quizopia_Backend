package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionQuestionRequest;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest.CompositionSectionRequest;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired private PlatformTransactionManager transactionManager;

    private long teacherUserId;
    private long expiredSessionId;
    private long runSeq;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('lc','lc@t','h','LC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        runSeq = Math.abs(System.nanoTime() % 1_000_000_000L);
        long subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB" + runSeq + "','Sub')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subjectId + ",'LCB" + runSeq + "','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long examId = examService.createExam(teacherUserId, new CreateExamRequest(subjectId, null, "LCE" + runSeq, "T", null)).id();
        examService.updateDraftComposition(teacherUserId, examId, new UpdateDraftCompositionRequest(0, null, null, List.of(
                new CompositionSectionRequest(0, "S", null, List.of(new CompositionQuestionRequest(q, 0, null))))));
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", examId);
        // Create expired OPEN session (past window)
        Instant pastStarts = Instant.now().minusSeconds(7200);
        Instant pastEnds = Instant.now().minusSeconds(3600);
        expiredSessionId = sessionService.createSession(teacherUserId, null,
                new CreateExamSessionRequest(examId, "EXP" + runSeq, "Expired", pastStarts, pastEnds, 1, null, null, null)).id();
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", expiredSessionId);
    }

    @Test
    void concurrentListLazyCloseNoOptimisticLockException() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () -> {
            start.await();
            try {
                sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
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
        mixedEntryPointsLockInIdOrder();
    }

    private void mixedEntryPointsLockInIdOrder() throws Exception {
        long examId = jdbc.queryForObject("SELECT exam_id FROM exam_sessions WHERE id=?", Long.class, expiredSessionId);
        // Lowest ID is SCHEDULED, higher IDs span OPEN and both SCHEDULED phases.
        for (int i = 0; i < 6; i++) {
            jdbc.update("INSERT INTO exam_sessions (exam_id, created_by, code, title, status, starts_at, ends_at, "
                    + "duration_minutes, visibility, opened_at) SELECT exam_id, created_by, ?, title, 'SCHEDULED', "
                    + "now()-interval '2 hours', now()-interval '1 hour', duration_minutes, visibility, NULL "
                    + "FROM exam_sessions WHERE id=?", "MIX-" + runSeq + "-" + i, expiredSessionId);
        }
        for (int round = 0; round < 2; round++) {
            jdbc.update("UPDATE exam_sessions SET status='SCHEDULED', opened_at=NULL, closed_at=NULL, "
                    + "ends_at=now()-interval '1 hour' WHERE exam_id=?", examId);
            jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now()-interval '2 hours' "
                    + "WHERE exam_id=? AND id<>? AND mod(id, 2)=0", examId, expiredSessionId);
            jdbc.update("UPDATE exam_sessions SET ends_at=now()+interval '1 hour' "
                    + "WHERE exam_id=? AND id<>? AND mod(id, 2)=1", examId, expiredSessionId);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch entered = new CountDownLatch(2);
            Future<?>[] calls = new Future<?>[2];
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(blocker -> {
                    jdbc.queryForObject("SELECT id FROM exam_sessions WHERE id=? FOR UPDATE", Long.class, expiredSessionId);
                    for (int i = 0; i < 2; i++) {
                        final boolean nested = (i == 0);
                        calls[i] = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                            jdbc.execute("SET LOCAL application_name = 'p1a-mixed-list'");
                            jdbc.execute("SET LOCAL lock_timeout = '10s'");
                            entered.countDown();
                            if (nested) sessionService.listSessionsForExam(teacherUserId, null, null, examId, 0, 20, null);
                            else sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
                        }));
                    }
                    try {
                        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                        int waiting;
                        do {
                            waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                                    + "WHERE application_name='p1a-mixed-list' AND wait_event_type='Lock'", Integer.class);
                            if (waiting == 2) break;
                            Thread.sleep(20);
                        } while (System.nanoTime() < deadline);
                        assertThat(waiting).as("both independent list transactions must wait for lowest ID").isEqualTo(2);
                        // If either entry locks a higher row first, NOWAIT fails here.
                        jdbc.queryForList("SELECT id FROM exam_sessions WHERE exam_id=? AND id<>? "
                                + "ORDER BY id FOR UPDATE NOWAIT", examId, expiredSessionId);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                });
                for (Future<?> call : calls) call.get(20, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_sessions WHERE exam_id=? "
                    + "AND (status='SCHEDULED' OR opened_at IS NULL OR (status='CLOSED' AND closed_at IS NULL))",
                    Long.class, examId)).isZero();
            var refreshed = jdbc.queryForList("SELECT * FROM exam_sessions WHERE exam_id=? ORDER BY id", examId);
            sessionService.listSessionsForExam(teacherUserId, null, null, examId, 0, 20, null);
            sessionService.listSessions(teacherUserId, null, null, null, 0, 20, null);
            assertThat(jdbc.queryForList("SELECT * FROM exam_sessions WHERE exam_id=? ORDER BY id", examId))
                    .isEqualTo(refreshed);
        }
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
