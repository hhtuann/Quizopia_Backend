package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.repository.AttemptAnswerRepository;
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
 * Repository-level true-concurrent UPSERT tests: directly exercises {@link AttemptAnswerRepository#upsertIfNewer}
 * under PostgreSQL row-lock contention on the UNIQUE (attempt_id, attempt_question_id) constraint —
 * WITHOUT the attempt pessimistic lock. Each thread uses its own TransactionTemplate. Latches prove
 * that Thread B is genuinely blocked on the row lock until Thread A commits.
 *
 * <p><b>Reliability contract (R2):</b> every worker Future is stored and joined via {@code get(timeout)};
 * worker exceptions (including AssertionError and rethrown InterruptedException) propagate to the main
 * thread as ExecutionException and fail the test — none are swallowed. The release latch is signalled
 * and the executor is shut down in a {@code finally} so a main-thread assertion failure cannot strand
 * a worker.
 *
 * <p><b>This class is the sole gate for A3.1 MEDIUM-1 closure.</b> Only if all 4 scenarios pass two
 * standalone runs can MEDIUM-1 be marked RESOLVED.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAnswerUpsertConcurrencyIntegrationTests {

    @Autowired private AttemptAnswerRepository answerRepo;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private MutableClock clock;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record Fixture(long attemptId, long aqId) {}

    // A — low first (holds), high waits: both affected=1, final = high.
    @Test
    void lowFirstHighWaits() throws Exception {
        Fixture f = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aUpserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1);
        AtomicReference<Integer> aAffected = new AtomicReference<>();
        AtomicReference<Integer> bAffected = new AtomicReference<>();
        try {
            Future<?> aFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                aAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"A\"}", 1));
                aUpserted.countDown();
                awaitRelease(releaseA);
            }));
            assertThat(awaitLatch(aUpserted, "aUpserted")).as("A must complete its UPSERT before B starts").isTrue();
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                bReady.countDown();
                bAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"B\"}", 5));
            }));
            assertThat(awaitLatch(bReady, "bReady")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked on UPSERT row lock while A holds the tx").isFalse();
            releaseA.countDown();
            aFuture.get(15, TimeUnit.SECONDS); // propagate A exceptions
            bFuture.get(15, TimeUnit.SECONDS); // propagate B exceptions
            assertThat(aAffected.get()).isEqualTo(1);
            assertThat(bAffected.get()).isEqualTo(1);
            assertSeq(f.aqId, 5);
            assertPayload(f.aqId, "B");
        } finally {
            releaseA.countDown();
            shutdown(pool);
            cleanup(f.attemptId);
        }
    }

    // B — high first (holds), low waits: high affected=1, low affected=0, final = high.
    @Test
    void highFirstLowWaits() throws Exception {
        Fixture f = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aUpserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1);
        AtomicReference<Integer> aAffected = new AtomicReference<>();
        AtomicReference<Integer> bAffected = new AtomicReference<>();
        try {
            Future<?> aFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                aAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"B\"}", 5));
                aUpserted.countDown();
                awaitRelease(releaseA);
            }));
            assertThat(awaitLatch(aUpserted, "aUpserted")).as("A must complete its UPSERT before B starts").isTrue();
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                bReady.countDown();
                bAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"A\"}", 1));
            }));
            assertThat(awaitLatch(bReady, "bReady")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked on UPSERT row lock while A holds the tx").isFalse();
            releaseA.countDown();
            aFuture.get(15, TimeUnit.SECONDS);
            bFuture.get(15, TimeUnit.SECONDS);
            assertThat(aAffected.get()).isEqualTo(1);
            assertThat(bAffected.get()).isEqualTo(0); // stale
            assertSeq(f.aqId, 5);
            assertPayload(f.aqId, "B");
        } finally {
            releaseA.countDown();
            shutdown(pool);
            cleanup(f.attemptId);
        }
    }

    // C — equal-sequence contention: exactly one affected=1, one affected=0.
    @Test
    void equalSequenceContention() throws Exception {
        Fixture f = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aUpserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1);
        AtomicReference<Integer> aAffected = new AtomicReference<>();
        AtomicReference<Integer> bAffected = new AtomicReference<>();
        try {
            Future<?> aFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                aAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"A\"}", 3));
                aUpserted.countDown();
                awaitRelease(releaseA);
            }));
            assertThat(awaitLatch(aUpserted, "aUpserted")).as("A must complete its UPSERT before B starts").isTrue();
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                bReady.countDown();
                bAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"B\"}", 3));
            }));
            assertThat(awaitLatch(bReady, "bReady")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked while A holds the tx").isFalse();
            releaseA.countDown();
            aFuture.get(15, TimeUnit.SECONDS);
            bFuture.get(15, TimeUnit.SECONDS);
            long accepted = (aAffected.get() == 1 ? 1 : 0) + (bAffected.get() == 1 ? 1 : 0);
            assertThat(accepted).as("exactly one accepted (equal sequence)").isEqualTo(1);
            assertRowCount(f.aqId, 1);
            assertSeq(f.aqId, 3);
        } finally {
            releaseA.countDown();
            shutdown(pool);
            cleanup(f.attemptId);
        }
    }

    // D — older non-null (holds), newer clear (waits): final payload NULL, seq=5.
    @Test
    void olderNonNullNewerClear() throws Exception {
        Fixture f = setup();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aUpserted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1);
        AtomicReference<Integer> aAffected = new AtomicReference<>();
        AtomicReference<Integer> bAffected = new AtomicReference<>();
        try {
            Future<?> aFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                aAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, "{\"selectedOptionKey\":\"A\"}", 1));
                aUpserted.countDown();
                awaitRelease(releaseA);
            }));
            assertThat(awaitLatch(aUpserted, "aUpserted")).as("A must complete its UPSERT before B starts").isTrue();
            Future<?> bFuture = pool.submit(() -> tx().executeWithoutResult(s -> {
                bReady.countDown();
                bAffected.set(answerRepo.upsertIfNewer(f.attemptId, f.aqId, null, 5));
            }));
            assertThat(awaitLatch(bReady, "bReady")).isTrue();
            assertThat(bFuture.isDone()).as("B must be blocked while A holds the tx").isFalse();
            releaseA.countDown();
            aFuture.get(15, TimeUnit.SECONDS);
            bFuture.get(15, TimeUnit.SECONDS);
            assertThat(aAffected.get()).isEqualTo(1);
            assertThat(bAffected.get()).isEqualTo(1);
            assertSeq(f.aqId, 5);
            Integer nullCount = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_question_id=? AND answer_payload IS NULL AND sequence_number=5", Integer.class, f.aqId);
            assertThat(nullCount).isEqualTo(1);
            assertRowCount(f.aqId, 1);
        } finally {
            releaseA.countDown();
            shutdown(pool);
            cleanup(f.attemptId);
        }
    }

    // === helpers ===

    /** Awaits a latch in the main thread, failing the test (not swallowing) on timeout or interrupt. */
    private static boolean awaitLatch(CountDownLatch latch, String label) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " await interrupted", e);
        }
    }

    /** Awaits a release latch inside a worker; rethrows interrupt/timeout as AssertionError so it propagates via the Future. */
    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("releaseA not signalled within 15s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("releaseA await interrupted", e);
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

    private void assertSeq(long aqId, long expected) {
        Long seq = jdbc.queryForObject("SELECT sequence_number FROM attempt_answers WHERE attempt_question_id=?", Long.class, aqId);
        assertThat(seq).isEqualTo(expected);
    }

    private void assertPayload(long aqId, String key) {
        String stored = jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqId);
        assertThat(stored).isEqualTo(key);
    }

    private void assertRowCount(long aqId, int expected) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_question_id=?", Integer.class, aqId);
        assertThat(count).isEqualTo(expected);
    }

    private Fixture setup() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        long[] ids = new long[2];
        tx().executeWithoutResult(status -> {
            clock.setInstant(now);
            long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('up" + s + "','up" + s + "@t.com','h','UP" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = insert("INSERT INTO schools (code, name) VALUES ('UPS" + s + "','Sch')");
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
            ids[0] = attempt; ids[1] = aq;
        });
        return new Fixture(ids[0], ids[1]);
    }

    private void cleanup(long attemptId) {
        try { tx().executeWithoutResult(s -> jdbc.update("DELETE FROM attempts WHERE id=?", attemptId)); } catch (Exception ignored) {}
    }

    private long insert(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
