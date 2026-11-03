package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.application.ExamSessionParticipantService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent committed transactions, production by-user API services, no profile provenance. */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class UserModerationConcurrencyIntegrationTests {
    @Autowired AttemptService attempts;
    @Autowired ExamSessionParticipantService participants;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired MutableClock clock;

    private record Fixture(long owner, long learner, long session) {}
    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    private long insert(String sql, Object... args) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
    }

    private Fixture fixture() {
        return tx().execute(status -> {
            Instant now = Instant.parse("2026-07-06T08:00:00Z");
            clock.setInstant(now);
            String tag = UUID.randomUUID().toString();
            long owner = insert("INSERT INTO users(username,email,password_hash,display_name) VALUES (?,?,'h','Owner')",
                    tag, tag + "@t.local");
            long learner = insert("INSERT INTO users(username,email,password_hash,display_name) VALUES (?,?,'h','Learner')",
                    "l" + tag, "l" + tag + "@t.local");
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='TEACHER'", owner);
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='TEACHER'", learner);
            long ws = insert("INSERT INTO workspaces(workspace_type,name,owner_user_id) VALUES ('PERSONAL','P',?)", owner);
            jdbc.update("INSERT INTO workspace_memberships(workspace_id,user_id,role,status) VALUES (?,?,'OWNER','ACTIVE')", ws, owner);
            long exam = insert("INSERT INTO exams(workspace_id,created_by_user_id,code,title,status,total_points,published_at) "
                    + "VALUES (?,?,'E','Exam','PUBLISHED',1,now())", ws, owner);
            long session = insert("INSERT INTO exam_sessions(exam_id,code,title,status,starts_at,ends_at,created_by,visibility,max_attempts,duration_minutes,opened_at) "
                    + "VALUES (?,'S','Session','OPEN',?,?,?,'PUBLIC',5,60,now())", exam,
                    java.sql.Timestamp.from(now.minusSeconds(60)), java.sql.Timestamp.from(now.plusSeconds(3600)), owner);
            // A completed historical attempt makes this profile-less user known to moderation.
            insert("INSERT INTO attempts(exam_session_id,user_id,attempt_number,status,started_at,deadline_at,submitted_at,submission_idempotency_key) "
                    + "VALUES (?,?,1,'SUBMITTED',?,?,?,'historical')", session, learner,
                    java.sql.Timestamp.from(now.minusSeconds(30)), java.sql.Timestamp.from(now.plusSeconds(3600)),
                    java.sql.Timestamp.from(now.minusSeconds(10)));
            return new Fixture(owner, learner, session);
        });
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    /** Prove the contender is actually waiting in PostgreSQL, rather than just not yet scheduled. */
    private void waitForDbLock(Future<?> contender) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query ILIKE '%exam_sessions%'", Integer.class);
            if (blocked != null && blocked > 0) return;
            assertThat(contender.isDone()).as("contender must wait on the session lock").isFalse();
            Thread.sleep(25);
        }
        throw new AssertionError("No PostgreSQL session-lock wait observed");
    }

    private void race(boolean firstBlock, boolean secondBlock) throws Exception {
        Fixture f = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> tx().executeWithoutResult(status -> {
                if (firstBlock) participants.blockUser(f.owner, f.session, f.learner);
                else participants.unblockUser(f.owner, f.session, f.learner);
                locked.countDown();
                await(release);
            }));
            await(locked);
            Future<?> second = pool.submit(() -> {
                if (secondBlock) participants.blockUser(f.owner, f.session, f.learner);
                else participants.unblockUser(f.owner, f.session, f.learner);
            });
            waitForDbLock(second);
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session_participants WHERE exam_session_id=? AND user_id=?",
                    Integer.class, f.session, f.learner)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM exam_session_participants WHERE exam_session_id=? AND user_id=?",
                    String.class, f.session, f.learner)).isEqualTo(secondBlock ? "BLOCKED" : "ELIGIBLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_session_participants WHERE exam_session_id=? "
                    + "AND student_profile_id IS NULL AND school_id IS NULL AND ((status='BLOCKED')=(blocked_at IS NOT NULL))",
                    Integer.class, f.session)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=? AND status='SUBMITTED'",
                    Integer.class, f.session)).isOne();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void concurrentBlockMaterializesExactlyOneUserRow() throws Exception { race(true, true); }
    @Test void blockThenUnblockObservesCommittedBlock() throws Exception { race(true, false); }
    @Test void unblockThenBlockObservesCommittedUnblock() throws Exception { race(false, true); }

    @Test
    void committedBlockPreventsWaitingStart() throws Exception {
        Fixture f = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> block = pool.submit(() -> tx().executeWithoutResult(status -> {
                participants.blockUser(f.owner, f.session, f.learner);
                locked.countDown(); await(release);
            }));
            await(locked);
            Future<String> start = pool.submit(() -> {
                try { attempts.startAttempt(f.learner, f.session, new StartAttemptRequest(null)); return "ALLOWED"; }
                catch (AttemptException e) { return e.getErrorCode().code(); }
            });
            waitForDbLock(start);
            release.countDown(); block.get(20, TimeUnit.SECONDS);
            assertThat(start.get(20, TimeUnit.SECONDS)).isEqualTo("ATTEMPT_NOT_ELIGIBLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts WHERE exam_session_id=?", Integer.class, f.session)).isOne();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
