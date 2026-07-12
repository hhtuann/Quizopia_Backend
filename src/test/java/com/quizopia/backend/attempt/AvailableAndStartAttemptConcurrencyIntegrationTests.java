package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.application.ExamSessionParticipantService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True production-concurrency tests: every overlapping side calls the real
 * production service
 * (AttemptService.startAttempt / ExamSessionService.closeSession /
 * ExamSessionParticipantService.blockParticipant)
 * inside an outer TransactionTemplate, so uncommitted row locks are genuinely
 * held and the other side
 * blocks on them. Readiness latches guarantee the "blocked" assertion is
 * observed only after the
 * blocking call has actually started. No raw SELECT FOR UPDATE stands in for a
 * production method.
 *
 * <p>
 * Lock order (start): session → participant; matches Day 6 and the close/block
 * services.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AvailableAndStartAttemptConcurrencyIntegrationTests {

    @Autowired
    private AttemptService attemptService;
    @Autowired
    private ExamSessionService examSessionService;
    @Autowired
    private ExamSessionParticipantService participantService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private PlatformTransactionManager txm;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txm);
    }

    /** Resolved setup identifiers for a concurrency scenario. */
    private record Chain(long studentUserId, long sessionId, long studentProfileId,
            long teacherUserId, long participantId, int sourceQuestionCount) {
    }

    // ============================================================
    // A. start×2 truly concurrent overlap
    // ============================================================

    @Test
    void concurrentStartX2Overlap() throws Exception {
        Chain c = setupChain(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            Future<StartAttemptResponse> f1 = pool.submit(() -> {
                ready.countDown();
                fire.await(10, TimeUnit.SECONDS);
                return attemptService.startAttempt(c.studentUserId, c.sessionId, new StartAttemptRequest(null));
            });
            Future<StartAttemptResponse> f2 = pool.submit(() -> {
                ready.countDown();
                fire.await(10, TimeUnit.SECONDS);
                return attemptService.startAttempt(c.studentUserId, c.sessionId, new StartAttemptRequest(null));
            });
            ready.await(10, TimeUnit.SECONDS);
            fire.countDown();
            StartAttemptResponse r1 = f1.get(30, TimeUnit.SECONDS);
            StartAttemptResponse r2 = f2.get(30, TimeUnit.SECONDS);

            // Exactly one new, one resume; same attemptId; attemptNumber=1.
            assertThat(r1.attemptId()).isEqualTo(r2.attemptId());
            long newCount = (!r1.resumed() ? 1 : 0) + (!r2.resumed() ? 1 : 0);
            long resumeCount = (r1.resumed() ? 1 : 0) + (r2.resumed() ? 1 : 0);
            assertThat(newCount).isOne();
            assertThat(resumeCount).isOne();
            assertThat(r1.attemptNumber()).isEqualTo(1);
            assertThat(r2.attemptNumber()).isEqualTo(1);

            // Exactly 1 IN_PROGRESS, 1 attempt row, distinct snapshot keys, no answers.
            long attemptId = r1.attemptId();
            assertThat(txCount("SELECT count(*) FROM attempts WHERE exam_session_id=? AND status='IN_PROGRESS'",
                    c.sessionId)).isEqualTo(1);
            assertThat(txCount("SELECT count(*) FROM attempts WHERE id=?", attemptId)).isEqualTo(1);
            assertThat(txCount("SELECT count(*) FROM attempt_questions WHERE attempt_id=?", attemptId))
                    .isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(DISTINCT display_order) FROM attempt_questions WHERE attempt_id=?",
                    attemptId)).isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(DISTINCT exam_question_id) FROM attempt_questions WHERE attempt_id=?",
                    attemptId)).isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", attemptId)).isZero();
        } finally {
            pool.shutdownNow();
            cleanup(c.sessionId);
        }
    }

    // ============================================================
    // B. close-first: production close holds session lock; start blocked then
    // rejected
    // ============================================================

    @Test
    void closeFirstBlocksStartThenRejects() throws Exception {
        Chain c = setupChain(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch closeReturned = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch startReady = new CountDownLatch(1);
        try {
            // Thread A (teacher): production close in an outer tx, hold before commit.
            Future<?> aFuture = pool.submit(() -> tx().executeWithoutResult(status -> {
                examSessionService.closeSession(c.teacherUserId, c.sessionId);
                closeReturned.countDown();
                awaitUninterruptibly(releaseClose);
            }));
            closeReturned.await(10, TimeUnit.SECONDS);

            // Thread B (student): production start — signal readiness right before the
            // blocking call.
            Future<AtomicReference<Exception>> bFuture = pool.submit(() -> {
                AtomicReference<Exception> ex = new AtomicReference<>();
                try {
                    tx().executeWithoutResult(s -> {
                        startReady.countDown();
                        attemptService.startAttempt(c.studentUserId, c.sessionId, new StartAttemptRequest(null));
                    });
                } catch (Exception e) {
                    ex.set(e);
                }
                return ex;
            });
            startReady.await(10, TimeUnit.SECONDS);
            assertThat(bFuture.isDone()).as("start must be blocked while close tx is uncommitted").isFalse();

            releaseClose.countDown(); // A commits CLOSED.
            aFuture.get(15, TimeUnit.SECONDS);
            AtomicReference<Exception> startEx = bFuture.get(15, TimeUnit.SECONDS);
            assertThat(startEx.get()).isInstanceOf(AttemptException.class);
            assertThat(((AttemptException) startEx.get()).getErrorCode().code()).isEqualTo("ATTEMPT_SESSION_NOT_OPEN");
            assertThat(txCount("SELECT count(*) FROM attempts WHERE exam_session_id=?", c.sessionId)).isZero();
        } finally {
            pool.shutdownNow();
            cleanup(c.sessionId);
        }
    }

    // ============================================================
    // C. start-first: production start holds locks; production close blocked, then
    // both succeed
    // ============================================================

    @Test
    void startFirstThenCloseBothSucceed() throws Exception {
        Chain c = setupChain(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startReturned = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch closeReady = new CountDownLatch(1);
        try {
            // Thread A (student): production start in an outer tx; locks held while
            // uncommitted.
            Future<StartAttemptResponse> aFuture = pool.submit(() -> tx().execute(status -> {
                StartAttemptResponse r = attemptService.startAttempt(c.studentUserId, c.sessionId,
                        new StartAttemptRequest(null));
                startReturned.countDown();
                awaitUninterruptibly(releaseStart);
                return r;
            }));
            startReturned.await(10, TimeUnit.SECONDS);

            // Thread B (teacher): production close — signal readiness right before the
            // blocking call.
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(status -> {
                closeReady.countDown();
                examSessionService.closeSession(c.teacherUserId, c.sessionId);
            }));
            closeReady.await(10, TimeUnit.SECONDS);
            assertThat(bFuture.isDone()).as("close must be blocked while start tx is uncommitted").isFalse();

            releaseStart.countDown(); // A commits the attempt.
            StartAttemptResponse aResp = aFuture.get(15, TimeUnit.SECONDS);
            bFuture.get(15, TimeUnit.SECONDS); // B proceeds and closes.

            assertThat(aResp.resumed()).isFalse();
            long attemptId = aResp.attemptId();
            assertThat(txCount("SELECT count(*) FROM attempts WHERE id=?", attemptId)).isEqualTo(1);
            assertThat(txCount("SELECT count(*) FROM attempts WHERE id=? AND status='IN_PROGRESS' AND attempt_number=1",
                    attemptId)).isEqualTo(1);
            assertThat(txCount("SELECT count(*) FROM attempt_questions WHERE attempt_id=?", attemptId))
                    .isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(DISTINCT exam_question_id) FROM attempt_questions WHERE attempt_id=?",
                    attemptId)).isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", attemptId)).isZero();
            assertThat(txStatus("SELECT status FROM exam_sessions WHERE id=?", c.sessionId)).isEqualTo("CLOSED");
        } finally {
            pool.shutdownNow();
            cleanup(c.sessionId);
        }
    }

    // ============================================================
    // D. block-first: production block holds locks; start blocked then rejected
    // ============================================================
    // ============================================================
    // E. start-first: production start holds locks; production block blocked, then
    // both succeed
    // ============================================================

    @Test
    void startFirstThenBlockBothSucceed() throws Exception {
        Chain c = setupChain(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startReturned = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch blockReady = new CountDownLatch(1);
        try {
            // Thread A (student): production start in an outer tx; locks held while
            // uncommitted.
            Future<StartAttemptResponse> aFuture = pool.submit(() -> tx().execute(status -> {
                StartAttemptResponse r = attemptService.startAttempt(c.studentUserId, c.sessionId,
                        new StartAttemptRequest(null));
                startReturned.countDown();
                awaitUninterruptibly(releaseStart);
                return r;
            }));
            startReturned.await(10, TimeUnit.SECONDS);

            // Thread B (teacher): production block (locks session→participant) — signal
            // readiness before the call.
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(status -> {
                blockReady.countDown();
                participantService.blockParticipant(c.teacherUserId, c.sessionId, c.participantId);
            }));
            blockReady.await(10, TimeUnit.SECONDS);
            assertThat(bFuture.isDone()).as("block must be blocked while start tx is uncommitted").isFalse();

            releaseStart.countDown(); // A commits the attempt.
            StartAttemptResponse aResp = aFuture.get(15, TimeUnit.SECONDS);
            bFuture.get(15, TimeUnit.SECONDS); // B proceeds and blocks the participant.

            assertThat(aResp.resumed()).isFalse();
            long attemptId = aResp.attemptId();
            assertThat(txCount("SELECT count(*) FROM attempts WHERE id=?", attemptId)).isEqualTo(1);
            assertThat(txCount("SELECT count(*) FROM attempt_questions WHERE attempt_id=?", attemptId))
                    .isEqualTo(c.sourceQuestionCount);
            assertThat(txCount("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", attemptId)).isZero();
            assertThat(txStatus("SELECT status FROM exam_session_participants WHERE id=?", c.participantId))
                    .isEqualTo("BLOCKED");
        } finally {
            pool.shutdownNow();
            cleanup(c.sessionId);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int txCount(String sql, Object... args) {
        return tx().execute(s -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private String txStatus(String sql, Object... args) {
        return tx().execute(s -> jdbc.queryForObject(sql, String.class, args));
    }

    /**
     * Builds a full chain with a dedicated TEACHER user owning the session (so
     * production
     * close/block authorize) and a STUDENT user who is the participant. Returns
     * every id the
     * scenarios need, including the participant PK for blockParticipant.
     */
    private Chain setupChain(int questionCount) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        long[] ids = new long[6];
        tx().executeWithoutResult(status -> {
            clock.setInstant(now);
            // Teacher (owns the session; authorized for EXAM_SESSION_CLOSE /
            // EXAM_SESSION_PARTICIPANT_BLOCK).
            long tu = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('t" + s + "','t"
                    + s + "@t.com','h','T" + s + "')");
            long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + tu + "," + teacherRoleId + ")");
            long sch = insert("INSERT INTO schools (code, name) VALUES ('S" + s + "','Sch')");
            long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + sch + ",'GL','G')");
            long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + sch + "," + gl
                    + ",'SUB','S')");
            long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + tu + "," + sch
                    + ",'TC" + s + "')");
            // Student (takes the attempt).
            long su = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('c" + s + "','c"
                    + s + "@t.com','h','C" + s + "')");
            long studentRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + su + "," + studentRoleId + ")");
            long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + su + "," + sch
                    + ",'SC" + s + "')");
            long bank = insert(
                    "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + sch
                            + "," + subj + "," + tp + ",'B" + s + "','Bank')");
            long examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                    + sch + "," + subj + "," + tp + ",'E" + s + "','t')");
            long ver = insert(
                    "INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES ("
                            + sch + "," + examId + ",1,'PUBLISHED',10,now()," + tu + ")");
            for (int i = 0; i < questionCount; i++) {
                long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + s
                        + i + "'," + tu + ")");
                long qv = insert(
                        "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES ("
                                + q + ",1,'SINGLE_CHOICE','c" + i + "',1,'{}'::jsonb," + tu + ")");
                long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S"
                        + i + "'," + i + ")");
                long eq = insert(
                        "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES ("
                                + ver + "," + sec + "," + q + "," + qv + ",'QC" + i + "','SINGLE_CHOICE','c" + i
                                + "',1," + i + ",'{}'::jsonb)");
                jdbc.update(
                        "INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES ("
                                + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),("
                                + eq + ",'D','d',false,3)");
            }
            long session = insert(
                    "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                            + sch + "," + ver + "," + tp + ",'SE" + s + "','t','OPEN','" + now.minusSeconds(3600)
                            + "','" + now.plusSeconds(7200) + "',2," + tu + ",'" + now.minusSeconds(3600) + "')");
            long participantId = insert(
                    "INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                            + sch + "," + session + "," + sp + "," + tu + ")");
            ids[0] = su;
            ids[1] = session;
            ids[2] = sp;
            ids[3] = tu;
            ids[4] = participantId;
            ids[5] = questionCount;
        });
        return new Chain(ids[0], ids[1], ids[2], ids[3], ids[4], (int) ids[5]);
    }

    private void cleanup(long sessionId) {
        try {
            tx().executeWithoutResult(s -> jdbc.update("DELETE FROM attempts WHERE exam_session_id=?", sessionId));
        } catch (Exception ignored) {
        }
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
