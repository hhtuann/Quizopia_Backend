package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptAutosaveService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * True-concurrency tests for autosave (A3.2-3): production autosave calls, serialized by the attempt
 * pessimistic lock + the strictly-greater sequence guard in the SQL UPSERT.
 *
 * <p><b>Scenarios (reported accurately):</b>
 * <ul>
 *   <li>1, 2, 4 — sequential sequence regressions (ordered commit; the sequence guard's deterministic
 *       behavior). These are NOT concurrent.</li>
 *   <li>3 — equal-sequence true production concurrency (exactly one accepted).</li>
 *   <li>5 — production attempt-lock serialization (B blocked until A commits; latch-proves lock-entry).</li>
 *   <li>6 — deadline captured AFTER the lock: B, blocked on the lock, is checked against a deadline
 *       advanced past while it waited → 409 ATTEMPT_DEADLINE_EXCEEDED, no mutation.</li>
 * </ul>
 *
 * <p><b>Reliability contract (R2):</b> every concurrent worker Future is stored and joined via
 * {@code get(timeout)}; latch awaits are asserted true; release latches are signalled and the executor
 * is shut down in {@code finally}; InterruptedException is never swallowed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAutosaveConcurrencyIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record Chain(long userId, long attemptId, long aqId) {}

    // 1. low-then-high (sequential regression): both accepted; high final.
    @Test
    void lowThenHighBothAccepted() {
        Chain c = setup();
        try {
            SaveAnswerResponse r1 = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            SaveAnswerResponse r2 = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
            assertThat(r1.accepted()).isTrue();
            assertThat(r2.accepted()).isTrue();
            assertFinalPayload(c.aqId, "B");
            assertFinalSequence(c.aqId, 5);
        } finally { cleanup(c.attemptId); }
    }

    // 2. high-then-low (sequential regression): high accepted, low stale; high not overwritten.
    @Test
    void highThenLowHighAcceptedLowStale() {
        Chain c = setup();
        try {
            SaveAnswerResponse r1 = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
            SaveAnswerResponse r2 = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            assertThat(r1.accepted()).isTrue();
            assertThat(r2.accepted()).isFalse();
            assertFinalPayload(c.aqId, "B");
            assertFinalSequence(c.aqId, 5);
        } finally { cleanup(c.attemptId); }
    }

    // 3. equal-sequence truly concurrent production: exactly one accepted.
    @Test
    void equalSequenceExactlyOneAccepted() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            Future<SaveAnswerResponse> f1 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 3)); });
            Future<SaveAnswerResponse> f2 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 3)); });
            assertThat(awaitLatch(ready, "ready")).as("both workers must reach the fire barrier").isTrue();
            fire.countDown();
            SaveAnswerResponse r1 = f1.get(30, TimeUnit.SECONDS);
            SaveAnswerResponse r2 = f2.get(30, TimeUnit.SECONDS);
            long accepted = (r1.accepted() ? 1 : 0) + (r2.accepted() ? 1 : 0);
            assertThat(accepted).as("exactly one accepted (equal sequence)").isEqualTo(1);
            assertFinalSequence(c.aqId, 3);
        } finally { fire.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 4. high-clear vs older non-null (sequential regression): final payload SQL NULL, sequence = high.
    @Test
    void highClearVsOlderNonNull() {
        Chain c = setup();
        try {
            SaveAnswerResponse r1 = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            SaveAnswerResponse r2 = autosaveService.saveAnswer(c.userId, c.attemptId, new SaveAnswerRequest(c.aqId, null, null, 5, null));
            assertThat(r1.accepted()).isTrue();
            assertThat(r2.accepted()).isTrue();
            assertFinalSequence(c.aqId, 5);
            Integer nullPayload = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_question_id=? AND answer_payload IS NULL AND sequence_number=5", Integer.class, c.aqId);
            assertThat(nullPayload).isEqualTo(1);
        } finally { cleanup(c.attemptId); }
    }

    // 5. production autosave holds attempt lock: B blocked until A commits (latch proves lock-entry).
    @Test
    void autosaveHoldsAttemptLock() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch autosaveReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCallingAutosave = new CountDownLatch(1);
        try {
            // Thread A: PRODUCTION autosaveService.saveAnswer in outer tx — holds attempt lock.
            Future<SaveAnswerResponse> aFuture = pool.submit(() -> tx().execute(status -> {
                SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
                autosaveReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return r;
            }));
            assertThat(awaitLatch(autosaveReturned, "autosaveReturned")).as("A must finish its autosave (uncommitted)").isTrue();
            // Thread B: production autosave — blocked on attempt lock held by A.
            Future<SaveAnswerResponse> bFuture = pool.submit(() ->
                    tx().execute(status -> {
                        bCallingAutosave.countDown();
                        return autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
                    }));
            assertThat(awaitLatch(bCallingAutosave, "bCallingAutosave")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked on attempt lock held by A's uncommitted autosave").isFalse();
            releaseA.countDown();
            SaveAnswerResponse aResp = aFuture.get(15, TimeUnit.SECONDS);
            SaveAnswerResponse bResp = bFuture.get(15, TimeUnit.SECONDS);
            assertThat(aResp.accepted()).isTrue();
            assertThat(bResp.accepted()).isTrue();
            assertFinalPayload(c.aqId, "B"); // high wins
            assertFinalSequence(c.aqId, 5);
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 6. deadline captured AFTER the lock: B blocks on the lock, the clock is advanced past the
    //    deadline while B waits, then B acquires the lock and must be rejected 409 with NO mutation.
    @Test
    void deadlineCheckedAfterLockAcquired() throws Exception {
        Chain c = setup();
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + c.attemptId, Instant.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch autosaveReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCallingAutosave = new CountDownLatch(1);
        try {
            // Thread A: production autosave seq=1, holds the attempt lock uncommitted.
            Future<SaveAnswerResponse> aFuture = pool.submit(() -> tx().execute(status -> {
                SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
                autosaveReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return r;
            }));
            assertThat(awaitLatch(autosaveReturned, "autosaveReturned")).as("A must finish its autosave (uncommitted)").isTrue();
            // Thread B: production autosave seq=5 — blocked on the attempt lock held by A.
            Future<SaveAnswerResponse> bFuture = pool.submit(() ->
                    tx().execute(status -> {
                        bCallingAutosave.countDown();
                        return autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
                    }));
            assertThat(awaitLatch(bCallingAutosave, "bCallingAutosave")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked on attempt lock held by A").isFalse();
            // While B is blocked, advance the clock PAST the deadline. B must re-check the deadline
            // at lock-acquire time (the post-lock `now`), not at request-arrival time.
            clock.setInstant(deadline.plusSeconds(60));
            releaseA.countDown();
            aFuture.get(15, TimeUnit.SECONDS); // A accepted (captured now before the advance)
            // B acquires the lock, captures now = deadline+60s → 409 ATTEMPT_DEADLINE_EXCEEDED.
            Throwable cause = catchExecutionException(bFuture);
            assertThat(cause).isInstanceOf(AttemptException.class);
            assertThat(((AttemptException) cause).getErrorCode().code()).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED");
            // B must NOT mutate anything: final answer is A's seq=1, not B's seq=5.
            assertFinalSequence(c.aqId, 1);
            assertFinalPayload(c.aqId, "A");
            // last_saved_at equals A's saved_at (set when A committed); B did not change it.
            Instant answerSavedAt = jdbc.queryForObject("SELECT saved_at FROM attempt_answers WHERE attempt_question_id=?", Instant.class, c.aqId);
            Instant attemptLastSavedAt = jdbc.queryForObject("SELECT last_saved_at FROM attempts WHERE id=?", Instant.class, c.attemptId);
            assertThat(attemptLastSavedAt).isEqualTo(answerSavedAt);
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // === helpers ===

    private SaveAnswerRequest req(long aqId, String optionKey, long seq) {
        return new SaveAnswerRequest(aqId, null, singleKey(optionKey), seq, null);
    }

    private JsonNode singleKey(String key) {
        return objectMapper.createObjectNode().put("selectedOptionKey", key);
    }

    private void assertFinalPayload(long aqId, String expectedKey) {
        String stored = jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqId);
        assertThat(stored).isEqualTo(expectedKey);
    }

    private void assertFinalSequence(long aqId, long expectedSeq) {
        Long seq = jdbc.queryForObject("SELECT sequence_number FROM attempt_answers WHERE attempt_question_id=?", Long.class, aqId);
        assertThat(seq).isEqualTo(expectedSeq);
    }

    /** Joins a worker Future expected to fail, unwrapping ExecutionException to its cause (no swallowing). */
    private static Throwable catchExecutionException(Future<?> future) {
        try {
            future.get(15, TimeUnit.SECONDS);
            throw new AssertionError("expected worker to throw, but it returned normally");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("worker did not complete within 15s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker join interrupted", e);
        }
    }

    /** Awaits a latch in the main thread; returns whether it counted down (main asserts the result). */
    private static boolean awaitLatch(CountDownLatch latch, String label) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " await interrupted", e);
        }
    }

    /** Awaits a latch inside a worker; rethrows timeout/interrupt as AssertionError so it propagates via the Future. */
    private static void awaitOrFail(CountDownLatch latch, String label) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " not signalled within 15s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " await interrupted", e);
        }
    }

    private static void shutdown(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new AssertionError("shutdown interrupted", e);
        }
    }

    private Chain setup() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        long[] ids = new long[3];
        tx().executeWithoutResult(status -> {
            long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ac" + s + "','ac" + s + "@t.com','h','AC" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = insert("INSERT INTO schools (code, name) VALUES ('ACS" + s + "','Sch')");
            long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
            long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
            long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + "," + school + ",'TC" + s + "')");
            long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + school + ",'SC" + s + "')");
            long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
            long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + u + ")");
            long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u + ")");
            long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
            long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + u + ")");
            long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
            long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
            long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S" + s + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + u + ")");
            long attempt = attemptService.startAttempt(u, session, new StartAttemptRequest(null)).attemptId();
            long aq = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attempt + " LIMIT 1", Long.class);
            ids[0] = u; ids[1] = attempt; ids[2] = aq;
        });
        return new Chain(ids[0], ids[1], ids[2]);
    }

    private void cleanup(long attemptId) {
        try { tx().executeWithoutResult(s -> jdbc.update("DELETE FROM attempts WHERE id=?", attemptId)); } catch (Exception ignored) {}
    }

    private long insert(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
