package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityCommentService;
import com.quizopia.backend.community.dto.CreateCommentRequest;
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
 * §6 race behavior of the #66 comment paths (ADR-007) at the service
 * level: concurrent same-user FIRST comments collapse onto ONE
 * identity (the ON CONFLICT (user_id) DO NOTHING profile upsert + the
 * post-upsert re-select — every comment snapshots the same stored
 * alias); a two-user ci-unique alias race yields exactly one 201 and
 * one 409 COMMUNITY_ALIAS_TAKEN with ZERO comment rows for the loser;
 * and the delete-then-edit tombstone race — a PATCH that raced a
 * committed DELETE gets 409 COMMUNITY_COMMENT_STATE_INVALID and can
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
    // concurrent same-user first comments → one identity
    // ==================================================================

    @Test
    void eightConcurrentFirstCommentsFromOneUserShareOneStoredIdentity() throws Exception {
        String alias = "Solo-" + System.nanoTime();
        Callable<String> task = () -> commentService.createComment(
                studentId, publicationId, new CreateCommentRequest("hello", alias))
                .getAuthorAlias();

        List<String> snapshots = runConcurrently(task, 8);

        // ONE profile row; EVERY comment snapshotted the SAME stored alias.
        assertThat(snapshots).hasSize(8).containsOnly(alias);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, studentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT author_alias) FROM community_comments WHERE author_user_id = ?",
                Integer.class, studentId)).isEqualTo(1);
    }

    // ==================================================================
    // two-user ci collision race → one 201, one 409, zero loser rows
    // ==================================================================

    @Test
    void twoRacingUsersOnOneAliasYieldExactlyOne201AndOne409() throws Exception {
        String alias = "Clash-" + System.nanoTime();
        long otherUser = newUser("cco", "STUDENT");

        AtomicInteger created = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        java.util.function.Consumer<Long> racer = userId -> {
            try {
                commentService.createComment(
                        userId, publicationId, new CreateCommentRequest("race", alias));
                created.incrementAndGet();
            } catch (CommunityException ex) {
                if (ex.getErrorCode() == CommunityErrorCode.COMMUNITY_ALIAS_TAKEN) {
                    taken.incrementAndGet();
                } else {
                    throw ex;
                }
            }
        };
        // The two pool threads race for DIFFERENT users from a shared
        // barrier; the assertions below never depend on which won.
        runMixed(() -> racer.accept(studentId), () -> racer.accept(otherUser));

        assertThat(created.get()).as("exactly one racer created the profile + comment").isEqualTo(1);
        assertThat(taken.get()).as("exactly one racer got 409 ALIAS_TAKEN").isEqualTo(1);

        // Exactly one profile row on the ci-unique alias; the loser left
        // NOTHING behind — no comment row, no profile row (§4 atomic).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE alias = ?",
                Integer.class, alias)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE publication_id = ?",
                Integer.class, publicationId)).isEqualTo(1);
        long winner = jdbc.queryForObject(
                "SELECT user_id FROM community_member_profiles WHERE alias = ?",
                Long.class, alias);
        long loser = winner == studentId ? otherUser : studentId;
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE author_user_id = ?",
                Integer.class, loser))
                .as("the 409 loser's comment POST failed atomically")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, loser)).isZero();
    }


    // ==================================================================
    // delete-then-edit race: the CAS never resurrects a tombstone
    // ==================================================================

    @Test
    void patchRacingACommittedDeleteGets409AndTheBodyStaysCleared() {
        long commentId = commentService.createComment(
                studentId, publicationId,
                new CreateCommentRequest("to delete", "Racer-" + System.nanoTime()))
                .getId();

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
                    new CreateCommentRequest("round " + round, "Racer-" + System.nanoTime()))
                    .getId();

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
        jdbc.update("DELETE FROM community_publications WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
        jdbc.update("DELETE FROM community_member_profiles WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
    }

}
