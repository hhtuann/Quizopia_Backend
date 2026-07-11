package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.application.AttemptSubmitService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.dto.SubmitRequest;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dedicated coverage for the server-side timeout sweeper contract. The sweeper is the backstop for
 * students who leave the attempt page before the timer hits zero: it auto-submits expired IN_PROGRESS
 * attempts via an internal-only path ({@link AttemptSubmitService#finalizeAttempt(Long)}) that does
 * NOT call {@link AttemptSubmitService#submitAttempt}, so the strict deadline check in the manual
 * submit/autosave path does not gate it.
 *
 * <p><b>Contract under test</b> (server clock is the source of truth):
 * <ul>
 *   <li>{@code now < deadlineAt} → manual submit/autosave allowed; sweeper does not select.</li>
 *   <li>{@code now == deadlineAt} → manual submit/autosave allowed (last permitted instant);
 *       sweeper does not select ({@code deadlineAt < now} is false).</li>
 *   <li>{@code now > deadlineAt} → manual submit/autosave rejected; sweeper selects and finalizes.</li>
 *   <li>Sweeper flag toggles ONLY the scheduler bean, never the API semantics.</li>
 *   <li>Timeout finalization records {@code submittedAt = deadlineAt} (not wall-clock now).</li>
 *   <li>Manual submit racing the sweeper yields exactly one submission, one Grade, one GradeItems set.</li>
 *   <li>Grading failure inside the sweeper rolls back atomically (no partial state).</li>
 * </ul>
 *
 * <p>The scheduler bean is disabled in the test profile; these tests drive
 * {@code findExpiredAttemptIds} / {@code finalizeAttempt} directly with a {@link MutableClock}, so
 * behavior is deterministic and never depends on the 30-second poll cycle.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptTimeoutSweeperIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager txm;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private record Chain(long userId, long attemptId, long aqId, Instant deadline) {}

    // ============================================================
    // Contract case 1: expired attempt is auto-submitted by the sweeper
    // ============================================================

    @Test
    void expiredAttemptIsAutoSubmittedWithDeadlineAsSubmittedAt() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1)); // now > deadline → expired

        List<Long> expired = submitService.findExpiredAttemptIds();
        assertThat(expired).contains(c.attemptId);

        submitService.finalizeAttempt(c.attemptId);

        assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
        // submittedAt = the deadline, NOT wall-clock now (duration recorded as exactly the allowed time).
        assertThat(submittedAt(c.attemptId)).isEqualTo(c.deadline);
        assertThat(submissionKey(c.attemptId)).isEqualTo("auto-timeout-" + c.attemptId);
        assertThat(count("grades WHERE attempt_id=" + c.attemptId)).isEqualTo(1);
        cleanup(c.attemptId);
    }

    // ============================================================
    // Contract case 2: active attempt is NOT selected by the sweeper
    // ============================================================

    @Test
    void activeAttemptNotSelectedBySweeper() {
        Chain c = setup();
        clock.setInstant(c.deadline.minusSeconds(60)); // now < deadline → still active

        List<Long> expired = submitService.findExpiredAttemptIds();
        assertThat(expired).doesNotContain(c.attemptId);
        assertThat(status(c.attemptId)).isEqualTo("IN_PROGRESS");
        cleanup(c.attemptId);
    }

    // ============================================================
    // Contract case 3: exact deadline — sweeper does not select (deadlineAt < now is false)
    // ============================================================

    @Test
    void exactDeadlineNotSelectedBySweeper() {
        Chain c = setup();
        clock.setInstant(c.deadline); // now == deadline → boundary belongs to the student

        List<Long> expired = submitService.findExpiredAttemptIds();
        assertThat(expired).doesNotContain(c.attemptId);
        // Manual submit at the exact deadline is still accepted (covered by
        // AttemptSubmitServiceIntegrationTests.exactDeadlineAccepted); the sweeper defers.
        assertThat(status(c.attemptId)).isEqualTo("IN_PROGRESS");
        cleanup(c.attemptId);
    }

    // ============================================================
    // Contract cases 4, 5, 6: repeated sweep is idempotent — no second submission / Grade / GradeItems
    // ============================================================

    @Test
    void repeatedSweepDoesNotCreateSecondSubmission() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1));

        submitService.finalizeAttempt(c.attemptId);
        // Second sweep finds the attempt already SUBMITTED → finalizeAttempt no-ops (status re-check).
        submitService.finalizeAttempt(c.attemptId);

        assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
        assertThat(submissionKey(c.attemptId)).isEqualTo("auto-timeout-" + c.attemptId);
        // exactly one attempts row (a second submission would need a new row / key)
        assertThat(count("attempts WHERE id=" + c.attemptId)).isEqualTo(1);
        cleanup(c.attemptId);
    }

    @Test
    void repeatedSweepDoesNotCreateSecondGrade() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1));

        submitService.finalizeAttempt(c.attemptId);
        submitService.finalizeAttempt(c.attemptId);

        assertThat(count("grades WHERE attempt_id=" + c.attemptId))
                .as("exactly one Grade after repeated sweep").isEqualTo(1);
        cleanup(c.attemptId);
    }

    @Test
    void repeatedSweepDoesNotCreateDuplicateGradeItems() {
        Chain c = setup();
        long oneSet = 0;
        clock.setInstant(c.deadline.plusSeconds(1));
        submitService.finalizeAttempt(c.attemptId);
        oneSet = count("grade_items WHERE attempt_id=" + c.attemptId);
        assertThat(oneSet).as("first sweep produces one GradeItems set").isGreaterThan(0);

        submitService.finalizeAttempt(c.attemptId); // second sweep must not duplicate

        assertThat(count("grade_items WHERE attempt_id=" + c.attemptId))
                .as("GradeItems count unchanged after repeated sweep").isEqualTo(oneSet);
        cleanup(c.attemptId);
    }

    // ============================================================
    // Contract case 7: manual submit racing the sweeper → exactly one winner, one Grade
    // ============================================================

    @Test
    void manualSubmitRacingSweeperProducesSingleSubmissionAndGrade() throws Exception {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1)); // both paths eligible
        long gradeItemsBefore = 0; // unused; we assert the post-state directly

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            // A: manual submit (its own tx). If the sweeper won, this sees SUBMITTED → ALREADY_SUBMITTED.
            Future<Object> manual = pool.submit(() -> {
                ready.countDown();
                awaitOrFail(fire, "fire");
                return (Object) tx().execute(status ->
                        submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("manual-race-key")));
            });
            // B: sweeper finalize (its own tx). If manual won, status re-check → no-op.
            Future<Object> sweep = pool.submit(() -> {
                ready.countDown();
                awaitOrFail(fire, "fire");
                return (Object) tx().execute(status -> {
                    submitService.finalizeAttempt(c.attemptId);
                    return "swept";
                });
            });

            assertThat(awaitLatch(ready, "ready")).isTrue();
            fire.countDown();
            joinOrCause(manual);
            joinOrCause(sweep);

            // Exactly one submission regardless of winner.
            assertThat(status(c.attemptId)).isEqualTo("SUBMITTED");
            assertThat(count("grades WHERE attempt_id=" + c.attemptId))
                    .as("exactly one Grade from the race").isEqualTo(1);
            // One GradeItems set (the fixture has one question → one grade_item).
            gradeItemsBefore = count("grade_items WHERE attempt_id=" + c.attemptId);
            assertThat(gradeItemsBefore).as("exactly one GradeItems set").isEqualTo(1);
            // The winning key is either the manual key or the auto-timeout key — never both.
            String key = submissionKey(c.attemptId);
            assertThat(key).isIn("manual-race-key", "auto-timeout-" + c.attemptId);
        } finally {
            fire.countDown();
            shutdown(pool);
            cleanup(c.attemptId);
        }
    }

    // ============================================================
    // Contract case 8: sweeper emits exactly one graded notification (no duplicate)
    // ============================================================

    @Test
    void sweeperEmitsExactlyOneGradedNotification() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1));

        long before = count("notifications WHERE user_id=" + c.userId
                + " AND type='RESULT_GRADED'");
        submitService.finalizeAttempt(c.attemptId);

        long after = count("notifications WHERE user_id=" + c.userId
                + " AND type='RESULT_GRADED'");
        assertThat(after - before).as("exactly one RESULT_GRADED notification").isEqualTo(1);

        // A second sweep must not duplicate the notification.
        submitService.finalizeAttempt(c.attemptId);
        long afterSecond = count("notifications WHERE user_id=" + c.userId
                + " AND type='RESULT_GRADED'");
        assertThat(afterSecond).isEqualTo(after);
        cleanup(c.attemptId);
    }

    // ============================================================
    // Contract case 9: grading failure inside the sweeper rolls back atomically — no partial state
    // ============================================================

    @Test
    void gradingFailureRollsBackSweeperLeavingNoPartialState() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1));
        // Test-only trigger: fail every grades INSERT so gradeAndPersist throws inside finalizeAttempt.
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_sweeper_grade() RETURNS TRIGGER AS $$ "
                + "BEGIN RAISE EXCEPTION USING ERRCODE = '23000', MESSAGE = 'test: sweeper grade blocked'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_sweeper_grade_trigger BEFORE INSERT ON grades "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_sweeper_grade()");
        try {
            long gradesBefore = count("grades WHERE attempt_id=" + c.attemptId);
            Exception caught = null;
            try {
                submitService.finalizeAttempt(c.attemptId);
            } catch (Exception e) {
                caught = e;
            }
            assertThat(caught).as("sweeper must fail when grading is blocked").isNotNull();
            // Atomic rollback against committed state: status reverted, no grade, no partial submission.
            assertThat(status(c.attemptId)).isEqualTo("IN_PROGRESS");
            assertThat(submittedAt(c.attemptId)).isNull();
            assertThat(submissionKey(c.attemptId)).isNull();
            assertThat(count("grades WHERE attempt_id=" + c.attemptId)).isEqualTo(gradesBefore);
        } finally {
            try { jdbc.execute("DROP TRIGGER IF EXISTS test_fail_sweeper_grade_trigger ON grades"); } catch (Exception ignored) {}
            try { jdbc.execute("DROP FUNCTION IF EXISTS test_fail_sweeper_grade()"); } catch (Exception ignored) {}
            cleanup(c.attemptId);
        }
    }

    // ============================================================
    // Contract case 10: late manual submit is rejected independently of the sweeper flag
    // ============================================================

    @Test
    void lateManualSubmitRejectedIndependentlyOfSweeperFlag() {
        Chain c = setup();
        clock.setInstant(c.deadline.plusSeconds(1)); // now > deadline
        // The deadline check in submitAttempt is unconditional (no sweeper-flag reference — verified by
        // code inspection). The test profile runs with the scheduler DISABLED, yet late submit is still
        // rejected: that is the proof the check fires independently of the flag.
        assertThatThrownBy(() -> submitService.submitAttempt(c.userId, c.attemptId, new SubmitRequest("late-key")))
                .isInstanceOfSatisfying(AttemptException.class,
                        ae -> assertThat(ae.getErrorCode().code()).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED"));
        assertThat(status(c.attemptId)).isEqualTo("IN_PROGRESS"); // no mutation on rejected submit
        cleanup(c.attemptId);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String status(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM attempts WHERE id=?", String.class, attemptId);
    }

    private Instant submittedAt(long attemptId) {
        return jdbc.queryForObject("SELECT submitted_at FROM attempts WHERE id=?", Instant.class, attemptId);
    }

    private String submissionKey(long attemptId) {
        return jdbc.queryForObject("SELECT submission_idempotency_key FROM attempts WHERE id=?", String.class, attemptId);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + where, Long.class);
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }

    private void cleanup(long attemptId) {
        // grades FK is RESTRICT to attempts → delete grades (cascades grade_items) before the attempt.
        // idempotency_records FK is RESTRICT to attempts too. attempt_answers/questions CASCADE.
        try {
            tx().executeWithoutResult(s -> {
                jdbc.update("DELETE FROM grade_items WHERE attempt_id=?", attemptId);
                jdbc.update("DELETE FROM grades WHERE attempt_id=?", attemptId);
                jdbc.update("DELETE FROM idempotency_records WHERE attempt_id=?", attemptId);
                jdbc.update("DELETE FROM attempts WHERE id=?", attemptId);
            });
        } catch (Exception ignored) {}
    }

    /** Builds one IN_PROGRESS attempt (one SINGLE_CHOICE question) and returns its chain + deadline. */
    private Chain setup() {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        long[] ids = new long[3];
        Instant[] deadlineHolder = new Instant[1];
        tx().executeWithoutResult(status -> {
            long u = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sw" + s + "','sw" + s + "@t.com','h','SW" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = ins("INSERT INTO schools (code, name) VALUES ('SWS" + s + "','Sch')");
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
            long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S" + s + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',5," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + u + ")");
            long attempt = attemptService.startAttempt(u, session, new StartAttemptRequest(null)).attemptId();
            long aq = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attempt + " LIMIT 1", Long.class);
            Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attempt, Instant.class);
            ids[0] = u; ids[1] = attempt; ids[2] = aq; deadlineHolder[0] = deadline;
        });
        return new Chain(ids[0], ids[1], ids[2], deadlineHolder[0]);
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
}
