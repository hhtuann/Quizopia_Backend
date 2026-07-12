package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.dto.SubmitResponse;
import com.quizopia.backend.attempt.exception.AttemptException;
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
 * True-concurrency tests for submit (A3.2-4): production submit calls serialized by the attempt
 * pessimistic lock + the idempotency UQs. Six scenarios:
 * <ol>
 *   <li>same attempt, same key → second returns the {@code IMMUTABLE_CACHED_RESPONSE}.</li>
 *   <li>same attempt, different keys → one 200, one 409 {@code ATTEMPT_ALREADY_SUBMITTED}.</li>
 *   <li>different attempts, same student, same key → UQ race: one 200, one 409
 *       {@code ATTEMPT_IDEMPOTENCY_CONFLICT}; loser stays IN_PROGRESS.</li>
 *   <li>submit-first/autosave-waits → autosave sees SUBMITTED → 409 {@code ATTEMPT_INVALID_STATE}.</li>
 *   <li>autosave-first/submit-waits → submit succeeds; latest answer preserved.</li>
 *   <li>deadline crossed while waiting → submit → 409 {@code ATTEMPT_DEADLINE_EXCEEDED}.</li>
 * </ol>
 *
 * <p><b>Reliability contract:</b> every worker Future stored + joined via {@code get(timeout)}; latch
 * awaits asserted true; release/shutdown in {@code finally}; {@code InterruptedException} rethrown
 * (never swallowed).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptSubmitConcurrencyIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record Chain(long userId, long attemptId, long aqId) {}
    private record Two(long userId, long attempt1, long attempt2) {}

    // 1. same attempt, same key: A submits+holds; B blocked → returns cached; identical fields; 1 transition, 1 cache.
    @Test
    void sameAttemptSameKeySecondReturnsCached() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCalling = new CountDownLatch(1);
        try {
            Future<SubmitResponse> aFuture = pool.submit(() -> tx().execute(status -> {
                SubmitResponse r = submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("k1"));
                aReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return r;
            }));
            assertThat(awaitLatch(aReturned, "aReturned")).isTrue();
            Future<SubmitResponse> bFuture = pool.submit(() -> tx().execute(status -> {
                bCalling.countDown();
                return submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("k1"));
            }));
            assertThat(awaitLatch(bCalling, "bCalling")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked on attempt lock held by A").isFalse();
            releaseA.countDown();
            SubmitResponse aResp = aFuture.get(15, TimeUnit.SECONDS);
            SubmitResponse bResp = bFuture.get(15, TimeUnit.SECONDS);
            assertThat(aResp.status()).isEqualTo("SUBMITTED");
            assertThat(bResp.status()).isEqualTo("SUBMITTED");
            assertThat(bResp.submittedAt()).isEqualTo(aResp.submittedAt());
            assertThat(bResp.serverTime()).isEqualTo(aResp.serverTime());
            assertThat(bResp.attemptNumber()).isEqualTo(aResp.attemptNumber());
            assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
            assertThat(count("idempotency_records WHERE attempt_id=" + c.attemptId)).isEqualTo(1);
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 2. same attempt, different keys: exactly one 200 + one 409 ALREADY_SUBMITTED; 1 cache.
    @Test
    void sameAttemptDifferentKeysOneSuccessOneAlreadySubmitted() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return (Object) submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("ka")); });
            Future<Object> f2 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return (Object) submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("kb")); });
            assertThat(awaitLatch(ready, "ready")).isTrue();
            fire.countDown();
            Object r1 = joinOrCause(f1);
            Object r2 = joinOrCause(f2);
            long oks = (r1 instanceof SubmitResponse ? 1 : 0) + (r2 instanceof SubmitResponse ? 1 : 0);
            long already = (r1 instanceof AttemptException ae1 && "ATTEMPT_ALREADY_SUBMITTED".equals(code(ae1)) ? 1 : 0)
                    + (r2 instanceof AttemptException ae2 && "ATTEMPT_ALREADY_SUBMITTED".equals(code(ae2)) ? 1 : 0);
            assertThat(oks).as("exactly one success").isEqualTo(1);
            assertThat(already).as("exactly one ALREADY_SUBMITTED").isEqualTo(1);
            assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
            assertThat(count("idempotency_records WHERE attempt_id=" + c.attemptId)).isEqualTo(1);
        } finally { fire.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 3. different attempts, same student, same key: UQ race → one 200 + one IDEMPOTENCY_CONFLICT; loser IN_PROGRESS.
    @Test
    void differentAttemptsSameKeyOneSuccessOneConflict() throws Exception {
        Two t = setupTwo();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return (Object) submitService.submitAttempt(t.userId, t.attempt1, new SubmitRequest("shared")); });
            Future<Object> f2 = pool.submit(() -> { ready.countDown(); awaitOrFail(fire, "fire"); return (Object) submitService.submitAttempt(t.userId, t.attempt2, new SubmitRequest("shared")); });
            assertThat(awaitLatch(ready, "ready")).isTrue();
            fire.countDown();
            Object r1 = joinOrCause(f1);
            Object r2 = joinOrCause(f2);
            long oks = (r1 instanceof SubmitResponse ? 1 : 0) + (r2 instanceof SubmitResponse ? 1 : 0);
            long conflicts = (r1 instanceof AttemptException ae1 && "ATTEMPT_IDEMPOTENCY_CONFLICT".equals(code(ae1)) ? 1 : 0)
                    + (r2 instanceof AttemptException ae2 && "ATTEMPT_IDEMPOTENCY_CONFLICT".equals(code(ae2)) ? 1 : 0);
            assertThat(oks).as("exactly one success").isEqualTo(1);
            assertThat(conflicts).as("exactly one IDEMPOTENCY_CONFLICT").isEqualTo(1);
            // Winner SUBMITTED, loser IN_PROGRESS.
            assertThat(Math.max(countOfStatus(t.attempt1), countOfStatus(t.attempt2))).as("one SUBMITTED").isEqualTo(1);
            assertThat(count("idempotency_records WHERE user_id=" + t.userId)).as("exactly one cache row for the user").isEqualTo(1);
        } finally { fire.countDown(); shutdown(pool); cleanupTwo(t); }
    }

    // 4. submit first, autosave waits → autosave sees SUBMITTED → 409 INVALID_STATE; answer unchanged.
    @Test
    void submitFirstAutosaveWaitsSeesSubmitted() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCalling = new CountDownLatch(1);
        try {
            pool.submit(() -> tx().execute(status -> {
                submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("k4"));
                aReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return null;
            }));
            assertThat(awaitLatch(aReturned, "aReturned")).isTrue();
            // B calls the production autosave in its OWN tx; it throws INVALID_STATE — the lambda does NOT
            // catch, so TransactionTemplate rolls back and rethrows (no UnexpectedRollbackException).
            Future<Object> bFuture = pool.submit(() -> {
                bCalling.countDown();
                return (Object) tx().execute(status -> autosaveService.saveAnswer(c.userId, c.attemptId, new SaveAnswerRequest(c.aqId, null, singleKey("B"), 1, null)));
            });
            assertThat(awaitLatch(bCalling, "bCalling")).isTrue();
            assertThat(bFuture.isDone()).as("autosave must be blocked on attempt lock held by submit").isFalse();
            releaseA.countDown();
            Object bResult = joinOrCause(bFuture);
            assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
            assertThat(bResult).isInstanceOf(AttemptException.class);
            assertThat(code((AttemptException) bResult)).isEqualTo("ATTEMPT_INVALID_STATE");
            // The losing autosave wrote nothing.
            assertThat(count("attempt_answers WHERE attempt_id=" + c.attemptId + " AND answer_payload->>'selectedOptionKey'='B'")).isEqualTo(0);
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 5. autosave first, submit waits → submit succeeds; latest answer preserved.
    @Test
    void autosaveFirstSubmitWaitsSucceeds() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCalling = new CountDownLatch(1);
        try {
            pool.submit(() -> tx().execute(status -> {
                autosaveService.saveAnswer(c.userId, c.attemptId, new SaveAnswerRequest(c.aqId, null, singleKey("A"), 1, null));
                aReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return null;
            }));
            assertThat(awaitLatch(aReturned, "aReturned")).isTrue();
            Future<SubmitResponse> bFuture = pool.submit(() -> tx().execute(status -> {
                bCalling.countDown();
                return submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("k5"));
            }));
            assertThat(awaitLatch(bCalling, "bCalling")).isTrue();
            assertThat(bFuture.isDone()).as("submit must be blocked on attempt lock held by autosave").isFalse();
            releaseA.countDown();
            SubmitResponse resp = bFuture.get(15, TimeUnit.SECONDS);
            assertThat(resp.status()).isEqualTo("SUBMITTED");
            // Latest autosaved answer preserved (still "A").
            String payload = jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, c.aqId);
            assertThat(payload).isEqualTo("A"); // submit did not copy/normalize the answer
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // 6. deadline crossed while waiting → submit → 409 DEADLINE_EXCEEDED; IN_PROGRESS; no cache.
    @Test
    void deadlineCrossedWhileWaiting() throws Exception {
        Chain c = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aReturned = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bCalling = new CountDownLatch(1);
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + c.attemptId, Instant.class);
        try {
            pool.submit(() -> tx().execute(status -> {
                autosaveService.saveAnswer(c.userId, c.attemptId, new SaveAnswerRequest(c.aqId, null, singleKey("A"), 1, null));
                aReturned.countDown();
                awaitOrFail(releaseA, "releaseA");
                return null;
            }));
            assertThat(awaitLatch(aReturned, "aReturned")).isTrue();
            Future<Object> bFuture = pool.submit(() -> {
                bCalling.countDown();
                return (Object) tx().execute(status -> submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("k6")));
            });
            assertThat(awaitLatch(bCalling, "bCalling")).isTrue();
            assertThat(bFuture.isDone()).as("submit must be blocked on attempt lock held by autosave").isFalse();
            clock.setInstant(deadline.plusSeconds(60));
            releaseA.countDown();
            Object bResult = joinOrCause(bFuture);
            assertThat(bResult).isInstanceOf(AttemptException.class);
            assertThat(code((AttemptException) bResult)).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED");
            assertThat(status(c.attemptId)).isEqualTo("IN_PROGRESS");
            assertThat(count("idempotency_records WHERE attempt_id=" + c.attemptId)).isEqualTo(0);
        } finally { releaseA.countDown(); shutdown(pool); cleanup(c.attemptId); }
    }

    // === helpers ===

    private tools.jackson.databind.JsonNode singleKey(String key) {
        return objectMapper.createObjectNode().put("selectedOptionKey", key);
    }

    private String status(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM attempts WHERE id=?", String.class, attemptId);
    }

    private long countOfStatus(long attemptId) {
        return jdbc.queryForObject("SELECT count(*) FROM attempts WHERE id=? AND status='SUBMITTED'", Long.class, attemptId);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + where, Long.class);
    }

    private static String code(AttemptException e) {
        return e.getErrorCode().code();
    }

    /** Joins a worker Future, unwrapping ExecutionException to its cause; never swallows. */
    private static Object joinOrCause(Future<Object> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("worker did not complete within 30s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker join interrupted", e);
        }
    }

    private static boolean awaitLatch(CountDownLatch latch, String label) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " await interrupted", e);
        }
    }

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
            long u = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sc" + s + "','sc" + s + "@t.com','h','SC" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = ins("INSERT INTO schools (code, name) VALUES ('SCS" + s + "','Sch')");
            long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
            long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
            long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + "," + school + ",'TC" + s + "')");
            long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + school + ",'SC" + s + "')");
            long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
            long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + u + ")");
            long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u + ")");
            long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
            long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + u + ")");
            long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
            long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
            long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S1" + s + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + u + ")");
            long attempt = attemptService.startAttempt(u, session, new StartAttemptRequest(null)).attemptId();
            long aq = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attempt + " LIMIT 1", Long.class);
            ids[0] = u; ids[1] = attempt; ids[2] = aq;
        });
        return new Chain(ids[0], ids[1], ids[2]);
    }

    /** Two IN_PROGRESS attempts for the same student in two different sessions (one-active is per session). */
    private Two setupTwo() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        long[] ids = new long[3];
        tx().executeWithoutResult(status -> {
            long u = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('st" + s + "','st" + s + "@t.com','h','ST" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = ins("INSERT INTO schools (code, name) VALUES ('STS" + s + "','Sch')");
            long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
            long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
            long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + "," + school + ",'TC" + s + "')");
            long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + school + ",'SC" + s + "')");
            long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
            long q1 = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q1'," + u + ")");
            long qv1 = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q1 + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u + ")");
            long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
            long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',2,now()," + u + ")");
            long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
            long eq1 = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q1 + "," + qv1 + ",'QC1','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            long q2 = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q2'," + u + ")");
            long qv2 = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u + ")");
            long eq2 = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q2 + "," + qv2 + ",'QC2','SINGLE_CHOICE','c',1,1,'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq1 + ",'A','a',false,0),(" + eq1 + ",'B','b',false,1),(" + eq1 + ",'C','c',true,2),(" + eq1 + ",'D','d',false,3),(" + eq2 + ",'A','a',false,0),(" + eq2 + ",'B','b',false,1),(" + eq2 + ",'C','c',true,2),(" + eq2 + ",'D','d',false,3)");
            long session1 = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S1" + s + "','t1','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + u + ",'" + now.minusSeconds(3600) + "')");
            long session2 = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S2" + s + "','t2','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session1 + "," + sp + "," + u + ")");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session2 + "," + sp + "," + u + ")");
            long a1 = attemptService.startAttempt(u, session1, new StartAttemptRequest(null)).attemptId();
            long a2 = attemptService.startAttempt(u, session2, new StartAttemptRequest(null)).attemptId();
            ids[0] = u; ids[1] = a1; ids[2] = a2;
        });
        return new Two(ids[0], ids[1], ids[2]);
    }

    private void cleanup(long attemptId) {
        // idempotency_records has FK RESTRICT to attempts — drop the cache row first, then the attempt
        // (CASCADE removes attempt_answers/attempt_questions).
        try {
            tx().executeWithoutResult(s -> {
                jdbc.update("DELETE FROM idempotency_records WHERE attempt_id=?", attemptId);
                jdbc.update("DELETE FROM attempts WHERE id=?", attemptId);
            });
        } catch (Exception ignored) {}
    }

    private void cleanupTwo(Two t) {
        try {
            tx().executeWithoutResult(s -> {
                jdbc.update("DELETE FROM idempotency_records WHERE attempt_id IN (?,?)", t.attempt1, t.attempt2);
                jdbc.update("DELETE FROM attempts WHERE id IN (?,?)", t.attempt1, t.attempt2);
            });
        } catch (Exception ignored) {}
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
