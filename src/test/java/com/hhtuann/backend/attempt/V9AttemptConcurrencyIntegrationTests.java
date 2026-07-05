package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.domain.model.AttemptAnswer;
import com.hhtuann.backend.attempt.repository.AttemptAnswerRepository;
import com.hhtuann.backend.attempt.repository.AttemptRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-transaction persistence tests on a real PostgreSQL 17 (Testcontainers):
 *
 * <ol>
 *   <li>Pessimistic-lock serialization — {@link AttemptRepository#findByIdForUpdate}
 *       (PESSIMISTIC_WRITE) makes a second transaction wait for the first to commit.</li>
 *   <li>Cross-transaction UPSERT — the native {@code ON CONFLICT ... WHERE sequence < EXCLUDED}
 *       guard keeps the higher sequence across separate committed transactions.</li>
 * </ol>
 *
 * <p>These tests commit their own data (they cannot use the class-level {@code @Transactional}
 * rollback, because the proof requires separate transactions). They use run-unique codes and
 * delete their attempt chain in a finally, so they do not pollute the shared container for the
 * other test classes. No {@code Thread.sleep} — synchronization is via latches.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V9AttemptConcurrencyIntegrationTests {

    @Autowired
    private AttemptRepository attemptRepo;
    @Autowired
    private AttemptAnswerRepository aaRepo;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txm;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txm);
    }

    @Test
    void findByIdForUpdateSerializesConcurrentTransactions() throws Exception {
        Chain chain = commitChain();
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        CountDownLatch t1Locked = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        CountDownLatch t2Started = new CountDownLatch(1);
        CountDownLatch t2Finished = new CountDownLatch(1);
        try {
            // Thread 1 locks the attempt row, then waits to be released (holding the lock).
            Future<?> f1 = pool.submit(() -> tx().executeWithoutResult(s -> {
                attemptRepo.findByIdForUpdate(chain.attemptId());
                t1Locked.countDown();
                try {
                    releaseT1.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(t1Locked.await(15, TimeUnit.SECONDS))
                    .as("thread 1 must acquire the row lock").isTrue();

            // Thread 2 starts its transaction and then tries to lock the SAME row — must block.
            // t2Finished is counted down only AFTER t2's tx commits (i.e. after findByIdForUpdate
            // returns, which cannot happen while t1 holds the lock). So while t1 holds, await is false.
            Future<?> f2 = pool.submit(() -> {
                tx().executeWithoutResult(s -> {
                    t2Started.countDown();
                    attemptRepo.findByIdForUpdate(chain.attemptId());
                });
                t2Finished.countDown();
            });
            assertThat(t2Started.await(15, TimeUnit.SECONDS))
                    .as("thread 2 must start").isTrue();
            // t2 is now blocked inside findByIdForUpdate: it cannot finish while t1 holds the lock.
            assertThat(t2Finished.await(750, TimeUnit.MILLISECONDS))
                    .as("thread 2 must be BLOCKED while thread 1 holds the pessimistic lock")
                    .isFalse();

            releaseT1.countDown(); // let t1 commit -> releases the row lock
            f1.get(15, TimeUnit.SECONDS); // t1 commits and releases
            // t2 now proceeds; signal completion by re-reading the row in a fresh tx after t2 done.
            f2.get(15, TimeUnit.SECONDS);
            // If we reach here, t2 acquired the lock after t1 released it => serialization works.
            // (If locking were not effective, the assertFalse above would have failed instead.)
            Boolean lockedAgain = tx().execute(s ->
                    attemptRepo.findByIdForUpdate(chain.attemptId()).isPresent());
            assertThat(lockedAgain).isTrue();
        } finally {
            pool.shutdownNow();
            cleanupAttempt(chain.attemptId());
        }
    }

    @Test
    void upsertHigherSequenceWinsAcrossTransactions() {
        Chain chain = commitChainWithQuestion();
        try {
            // tx A: insert seq 5
            tx().executeWithoutResult(s -> aaRepo.upsertIfNewer(chain.attemptId(), chain.attemptQuestionId(),
                    "{\"selectedOptionKey\":\"FIVE\"}", 5L));
            // tx B: smaller seq 2 — must be ignored (stale)
            tx().executeWithoutResult(s -> aaRepo.upsertIfNewer(chain.attemptId(), chain.attemptQuestionId(),
                    "{\"selectedOptionKey\":\"TWO\"}", 2L));
            // tx C: equal seq 5 — must be ignored (stale)
            tx().executeWithoutResult(s -> aaRepo.upsertIfNewer(chain.attemptId(), chain.attemptQuestionId(),
                    "{\"selectedOptionKey\":\"FIVE2\"}", 5L));
            // tx D: higher seq 9 — wins
            tx().executeWithoutResult(s -> aaRepo.upsertIfNewer(chain.attemptId(), chain.attemptQuestionId(),
                    "{\"selectedOptionKey\":\"NINE\"}", 9L));
            // read back in a fresh tx — sequence 9 / payload NINE persisted, others ignored
            AttemptAnswer stored = tx().execute(s -> aaRepo
                    .findByAttemptIdAndAttemptQuestionId(chain.attemptId(), chain.attemptQuestionId())
                    .orElseThrow());
            assertThat(stored.getSequenceNumber()).isEqualTo(9L);
            assertThat(stored.getAnswerPayload().path("selectedOptionKey").asString()).isEqualTo("NINE");
        } finally {
            cleanupAttempt(chain.attemptId());
        }
    }

    // ============================================================
    // Helpers — committed V8 chain (run-unique codes), cascade cleanup
    // ============================================================

    private record Chain(long attemptId, long attemptQuestionId) {}

    /** Commits a full V8 chain + one IN_PROGRESS attempt (no attempt_question). */
    private Chain commitChain() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long attemptId = tx().execute(status -> {
            long u = insert("INSERT INTO users (username, email, password_hash, display_name) "
                    + "VALUES ('u" + s + "','u" + s + "@t.com','h','U" + s + "')");
            long sch = insert("INSERT INTO schools (code, name) VALUES ('S" + s + "','Sch')");
            long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + sch + ",'GL','G')");
            long sub = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                    + "VALUES (" + sch + "," + gl + ",'SUB','S')");
            long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                    + "VALUES (" + u + "," + sch + ",'TC" + s + "')");
            long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                    + "VALUES (" + u + "," + sch + ",'SC" + s + "')");
            long examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                    + "VALUES (" + sch + "," + sub + "," + tp + ",'E" + s + "','t')");
            long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                    + "published_at, created_by) VALUES (" + sch + "," + examId + ",1,'PUBLISHED',10,now()," + u + ")");
            long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, "
                    + "status, starts_at, ends_at, created_by, opened_at) VALUES (" + sch + "," + ver + "," + tp + ",'SE"
                    + s + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + u + ",now())");
            long attempt = insert("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, "
                    + "attempt_number, started_at, deadline_at) VALUES (" + sch + "," + session + "," + sp + "," + ver
                    + ",1,now(),now()+interval '1 hour')");
            return attempt;
        });
        return new Chain(attemptId, 0L);
    }

    /** Commits a chain that additionally contains one attempt_question (for the UPSERT test). */
    private Chain commitChainWithQuestion() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long[] ids = new long[3];
        tx().executeWithoutResult(status -> {
            long u = insert("INSERT INTO users (username, email, password_hash, display_name) "
                    + "VALUES ('u" + s + "','u" + s + "@t.com','h','U" + s + "')");
            long sch = insert("INSERT INTO schools (code, name) VALUES ('S" + s + "','Sch')");
            long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + sch + ",'GL','G')");
            long sub = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                    + "VALUES (" + sch + "," + gl + ",'SUB','S')");
            long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                    + "VALUES (" + u + "," + sch + ",'TC" + s + "')");
            long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                    + "VALUES (" + u + "," + sch + ",'SC" + s + "')");
            long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                    + "VALUES (" + sch + "," + sub + "," + tp + ",'B" + s + "','Bank')");
            long q = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                    + "VALUES (" + bank + ",'Q" + s + "'," + u + ")");
            long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                    + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb,"
                    + u + ")");
            long examId = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                    + "VALUES (" + sch + "," + sub + "," + tp + ",'E" + s + "','t')");
            long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                    + "published_at, created_by) VALUES (" + sch + "," + examId + ",1,'PUBLISHED',10,now()," + u + ")");
            long section = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'Sec',0)");
            long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                    + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                    + "VALUES (" + ver + "," + section + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, "
                    + "status, starts_at, ends_at, created_by, opened_at) VALUES (" + sch + "," + ver + "," + tp + ",'SE"
                    + s + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + u + ",now())");
            long attempt = insert("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, "
                    + "attempt_number, started_at, deadline_at) VALUES (" + sch + "," + session + "," + sp + "," + ver
                    + ",1,now(),now()+interval '1 hour')");
            long aq = insert("INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, default_points, "
                    + "display_order) VALUES (" + attempt + "," + eq + ",'SINGLE_CHOICE',1,0)");
            ids[0] = attempt;
            ids[1] = aq;
        });
        return new Chain(ids[0], ids[1]);
    }

    /** Deletes the attempt (CASCADE removes attempt_questions/answers); prereq chain is run-unique and harmless. */
    private void cleanupAttempt(long attemptId) {
        try {
            tx().executeWithoutResult(s -> jdbc.update("DELETE FROM attempts WHERE id = ?", attemptId));
        } catch (Exception ignored) {
            // best-effort cleanup; codes are run-unique so leftover rows cannot collide.
        }
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
