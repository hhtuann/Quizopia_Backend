package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityCommentService;
import com.quizopia.backend.community.dto.CreateCommentRequest;
import com.quizopia.backend.community.dto.ReportCommentRequest;
import com.quizopia.backend.community.dto.UpdateCommentRequest;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6 race behavior of the #66 comment paths (ADR-007, Rev 4 / #72) at
 * the service level: concurrent same-user comments all succeed and
 * carry the SAME server-derived display name (no alias identity to
 * collapse — the V31 profile upsert is gone); two racing users with
 * IDENTICAL display names BOTH succeed (duplicate names are legal —
 * the old one-winner 409 ALIAS_TAKEN contract is retired); the
 * same-reporter comment-report dedupe race — two concurrent
 * reportComment calls collapse onto ONE OPEN row (one 201 + one 200
 * carrying the SAME report id); and the delete-then-edit tombstone
 * race — a PATCH that raced a committed DELETE gets 409
 * COMMUNITY_COMMENT_STATE_INVALID and can
 * never resurrect the cleared body.
 *
 * <p>Deliberately NOT {@code @Transactional} so every service call runs
 * in its own committed transaction and the upserts race for real
 * ({@code CommunitySocialConcurrencyIntegrationTests} pattern);
 * {@code @DirtiesContext} keeps the committed fixtures out of the
 * shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityCommentConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityCommentService commentService;

    private long teacherId;
    private long studentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("cct", "TEACHER");
        studentId = newUser("ccs", "STUDENT");
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'CCX%s', 'Comment race exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Comment race publication', 'Race-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    // ==================================================================
    // concurrent same-user comments → all succeed, one shared name
    // ==================================================================

    @Test
    void eightConcurrentCommentsFromOneUserAllCarryTheSameDisplayName() throws Exception {
        Callable<String> task = () -> commentService.createComment(
                studentId, publicationId, new CreateCommentRequest("hello"))
                .authorName();

        List<String> names = runConcurrently(task, 8);

        // Every comment succeeded with the SAME server-derived name; the
        // retired alias machinery (member profiles) stays untouched.
        assertThat(names).hasSize(8).containsOnly("CC");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, studentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE author_user_id = ?",
                Integer.class, studentId)).isEqualTo(8);
    }

    // ==================================================================
    // two users with identical display names racing → BOTH succeed
    // ==================================================================

    @Test
    void twoRacingUsersWithIdenticalDisplayNamesBothCreateTheirComments() throws Exception {
        long otherUser = newUser("cco", "STUDENT");
        jdbc.update("UPDATE users SET display_name = 'CC' WHERE id IN (?, ?)",
                studentId, otherUser);

        AtomicInteger created = new AtomicInteger();
        java.util.function.Consumer<Long> racer = userId -> {
            commentService.createComment(userId, publicationId, new CreateCommentRequest("race"));
            created.incrementAndGet();
        };
        // The two pool threads race for DIFFERENT users from a shared
        // barrier; duplicate display names are legal — no loser, no 409.
        runMixed(() -> racer.accept(studentId), () -> racer.accept(otherUser));

        assertThat(created.get()).as("both racers created their comment").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE publication_id = ?",
                Integer.class, publicationId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT author_user_id) FROM community_comments WHERE publication_id = ?",
                Integer.class, publicationId))
                .as("each racing user authored exactly their own row")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles", Integer.class))
                .as("the retired alias surface receives no writes")
                .isZero();
    }


    // ==================================================================
    // delete-then-edit race: the CAS never resurrects a tombstone
    // ==================================================================

    @Test
    void patchRacingACommittedDeleteGets409AndTheBodyStaysCleared() {
        long commentId = commentService.createComment(
                studentId, publicationId,
                new CreateCommentRequest("to delete"))
                .commentId();

        // Deterministic interleaving of "PATCH loaded VISIBLE, DELETE
        // committed, PATCH UPDATE fires": the tombstone commits first
        // (this suite has no wrapping transaction), then the PATCH's
        // state-guarded CAS finds no VISIBLE row.
        commentService.deleteComment(studentId, commentId);

        Assertions.assertThatThrownBy(() -> commentService.updateComment(
                studentId, commentId, new UpdateCommentRequest("edit after delete")))
                .isInstanceOfSatisfying(CommunityException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID));
        assertThat(jdbc.queryForObject(
                "SELECT body FROM community_comments WHERE id = ?",
                String.class, commentId))
                .as("the cleared tombstone body is never resurrected")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM community_comments WHERE id = ?",
                String.class, commentId)).isEqualTo("DELETED");
    }

    @Test
    void concurrentPatchDeletePairsNeverResurrectTheTombstone() throws Exception {
        for (int round = 0; round < 5; round++) {
            long commentId = commentService.createComment(
                    studentId, publicationId,
                    new CreateCommentRequest("round " + round))
                    .commentId();

            // PATCH and DELETE released from a shared barrier: whichever
            // order they land in, the PATCH is 200 (won the race BEFORE the
            // delete committed) or 409 (delete committed first) — never an
            // error — and the delete always wins the end state.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch ready = new CountDownLatch(2);
            Future<Integer> patchOutcome = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    commentService.updateComment(
                            studentId, commentId, new UpdateCommentRequest("raced edit"));
                    return 200;
                } catch (CommunityException ex) {
                    assertThat(ex.getErrorCode())
                            .isEqualTo(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID);
                    return 409;
                }
            });
            Future<Void> deleteOutcome = pool.submit(() -> {
                ready.countDown();
                start.await();
                commentService.deleteComment(studentId, commentId);
                return null;
            });
            ready.await();
            start.countDown();
            int patch = patchOutcome.get(60, TimeUnit.SECONDS);
            deleteOutcome.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(patch).as("PATCH resolves 200-before-delete or 409-after").isIn(200, 409);

            // Post-conditions: the tombstone is terminal, body cleared for good.
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM community_comments WHERE id = ?",
                    String.class, commentId)).isEqualTo("DELETED");
            assertThat(jdbc.queryForObject(
                    "SELECT body FROM community_comments WHERE id = ?",
                    String.class, commentId)).isEmpty();
            Assertions.assertThatThrownBy(() -> commentService.updateComment(
                    studentId, commentId, new UpdateCommentRequest("late edit")))
                    .isInstanceOfSatisfying(CommunityException.class,
                            ex -> assertThat(ex.getErrorCode())
                                    .isEqualTo(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID));
        }
    }


    // ==================================================================
    // same-reporter report dedupe race → one OPEN row, same id
    // ==================================================================

    @Test
    void concurrentSameReporterCommentReportsYieldExactlyOneOpenRow() throws Exception {
        long commentId = commentService.createComment(
                studentId, publicationId,
                new CreateCommentRequest("report race target"))
                .commentId();
        long reporterId = newUser("ccr", "STUDENT");

        Callable<String> task = () -> {
            try {
                CommunityCommentService.SubmittedCommentReport submitted =
                        commentService.reportComment(
                                reporterId, commentId,
                                new ReportCommentRequest("SPAM", "race"), "10.0.0.1");
                return (submitted.created() ? "CREATED:" : "DEDUPED:")
                        + submitted.report().reportId();
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };

        List<String> results = runConcurrently(task, 2);

        // One real insert (201) and one dedupe (200) — both carry the
        // SAME report id, never two OPEN rows (publication-report twin
        // pattern, CommunityModerationConcurrencyIntegrationTests).
        assertThat(results.stream().map(r -> r.substring(0, r.indexOf(':'))).toList())
                .containsExactlyInAnyOrder("CREATED", "DEDUPED");
        assertThat(results.get(0).substring(results.get(0).indexOf(':') + 1))
                .as("the 200 dedupe returns the same report the 201 created")
                .isEqualTo(results.get(1).substring(results.get(1).indexOf(':') + 1));
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_comment_reports
                        WHERE comment_id = ? AND reporter_user_id = ? AND status = 'OPEN'
                        """, Integer.class, commentId, reporterId))
                .as("exactly one OPEN report survives the race")
                .isEqualTo(1);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private <T> List<T> runConcurrently(Callable<T> task, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    /** Two tasks racing from a shared barrier (one thread each). */
    private void runMixed(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        Future<?> f1 = pool.submit(() -> {
            ready.countDown();
            awaitStart(start);
            first.run();
        });
        Future<?> f2 = pool.submit(() -> {
            ready.countDown();
            awaitStart(start);
            second.run();
        });
        ready.await();
        start.countDown();
        f1.get(60, TimeUnit.SECONDS);
        f2.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("racer interrupted at the barrier", ex);
        }
    }

    private long newUser(String prefix, String role) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'CC', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix + System.nanoTime(), prefix, prefix, System.nanoTime(),
                System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    // Non-@Transactional suite: committed fixtures would leak into the
    // shared container and break the exact-count catalog suites (which
    // run earlier in the default run order). Only the rows this class
    // created (created_at >= class load) are removed; publication
    // children CASCADE along.
    private static final java.time.Instant CLASS_START = java.time.Instant.now();

    @org.junit.jupiter.api.AfterEach
    void cleanCommittedFixtures() {
        jdbc.update("DELETE FROM community_publications WHERE created_at >= ?", java.sql.Timestamp.from(CLASS_START));
        jdbc.update("DELETE FROM community_member_profiles WHERE created_at >= ?", java.sql.Timestamp.from(CLASS_START));
    }

}
