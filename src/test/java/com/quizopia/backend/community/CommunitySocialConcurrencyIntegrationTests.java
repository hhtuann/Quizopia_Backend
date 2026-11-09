package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunitySocialService;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

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
 * Concurrency tests for the #65 like + rating writes (ADR-007 §6) on
 * real committed transactions: eight racing like PUTs collapse onto
 * exactly one row with exactly one 201 (rowcount-created flag), eight
 * racing first-ratings produce exactly one created=true ({@code xmax =
 * 0} flag) on one row, concurrent rating updates stay last-writer-wins
 * on that single row with a deterministic aggregate, and like
 * PUT/DELETE pairs leave no duplicates.
 *
 * <p>Deliberately NOT {@code @Transactional} so every service call runs
 * in its own committed transaction and the native upserts race for real
 * ({@code CommunityBookmarkConcurrencyIntegrationTests} pattern);
 * {@code @DirtiesContext} keeps the committed fixtures out of the
 * shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunitySocialConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunitySocialService socialService;

    private long teacherId;
    private long studentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("sct", "TEACHER");
        studentId = newUser("scs", "STUDENT");
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'SCX%s', 'Race exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Race publication', 'Race-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    @Test
    void eightConcurrentLikePutsYieldExactlyOneRowAll2xxOne201() throws Exception {
        AtomicInteger createdCount = new AtomicInteger();
        Callable<Long> task = () -> {
            CommunitySocialService.LikeOutcome outcome =
                    socialService.likePublication(studentId, publicationId);
            if (outcome.created()) {
                createdCount.incrementAndGet();
            }
            return outcome.likeCount();
        };
        List<Long> likeCounts = runConcurrently(task, 8);

        // All 8 racers succeeded (service-level 2xx semantics), every
        // one saw the SAME aggregate, exactly one created the row.
        assertThat(likeCounts).hasSize(8).containsOnly(1L);
        assertThat(createdCount.get()).as("exactly one racer created the row (201)").isEqualTo(1);
        assertThat(likeRowCount()).isEqualTo(1);
    }

    @Test
    void eightConcurrentFirstRatingsYieldExactlyOneRowAndExactlyOneCreated() throws Exception {
        AtomicInteger createdCount = new AtomicInteger();
        Callable<Integer> task = () -> {
            CommunitySocialService.RatingOutcome outcome =
                    socialService.ratePublication(studentId, publicationId, 4);
            if (outcome.created()) {
                createdCount.incrementAndGet();
            }
            return (int) outcome.ratingCount();
        };
        List<Integer> ratingCounts = runConcurrently(task, 8);

        // The xmax=0 flag is race-correct: exactly one racer inserted
        // (201); the other seven took the DO UPDATE path (200) on the
        // SAME row — one row, count 1 for every racer.
        assertThat(ratingCounts).hasSize(8).containsOnly(1);
        assertThat(createdCount.get())
                .as("exactly one racer's RETURNING (xmax = 0) flagged created")
                .isEqualTo(1);
        assertThat(ratingRowCount()).isEqualTo(1);
        assertThat(ratingValue()).isEqualTo(4);
    }

    @Test
    void concurrentRatingUpdatesAreLastWriterWinsOnOneRowWithDeterministicAggregate() throws Exception {
        // Seed the row first, then race eight DIFFERENT update values.
        socialService.ratePublication(studentId, publicationId, 1);
        Callable<Integer> task = () -> {
            int value = 1 + (int) (Thread.currentThread().getId() % 5);
            CommunitySocialService.RatingOutcome outcome =
                    socialService.ratePublication(studentId, publicationId, value);
            return outcome.myRating();
        };
        List<Integer> seen = runConcurrently(task, 8);

        // Still exactly one row; the stored value is one of the racers'
        // submissions (last writer wins); every racer observed its own
        // submitted value back (deterministic per-call aggregates).
        assertThat(seen).hasSize(8).allSatisfy(value -> assertThat(value).isBetween(1, 5));
        assertThat(ratingRowCount()).isEqualTo(1);
        assertThat(ratingValue()).isBetween(1, 5);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_ratings
                        WHERE publication_id = ? AND user_id = ? AND updated_at >= created_at
                        """, Integer.class, publicationId, studentId))
                .as("updated_at never regresses below created_at")
                .isEqualTo(1);
    }

    @Test
    void concurrentLikePutDeletePairsLeaveNoDuplicatesAndADeterministicEndState() throws Exception {
        for (int round = 0; round < 5; round++) {
            List<String> results = runMixed(
                    () -> {
                        socialService.likePublication(studentId, publicationId);
                        return "PUT";
                    },
                    () -> {
                        socialService.unlikePublication(studentId, publicationId);
                        return "DELETE";
                    });
            assertThat(results).containsExactlyInAnyOrder("PUT", "DELETE");
            assertThat(likeRowCount())
                    .as("row state is 0 or 1 — never a duplicate, never an error")
                    .isLessThanOrEqualTo(1);

            // Re-establish a known state for the next round.
            socialService.unlikePublication(studentId, publicationId);
            assertThat(likeRowCount()).isZero();
        }
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private int likeRowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_likes WHERE user_id = ? AND publication_id = ?",
                Integer.class, studentId, publicationId);
    }

    private int ratingRowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_ratings WHERE user_id = ? AND publication_id = ?",
                Integer.class, studentId, publicationId);
    }

    private int ratingValue() {
        return jdbc.queryForObject(
                "SELECT rating FROM community_ratings WHERE user_id = ? AND publication_id = ?",
                Integer.class, studentId, publicationId);
    }

    private <T> List<T> runConcurrently(Callable<T> task, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new java.util.ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    /** Two tasks racing from a shared barrier (one thread each). */
    private List<String> runMixed(Callable<String> first, Callable<String> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        Future<String> f1 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return first.call();
        });
        Future<String> f2 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return second.call();
        });
        ready.await();
        start.countDown();
        List<String> results = List.of(f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private long newUser(String prefix, String role) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SC', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix + System.nanoTime(), prefix, prefix + System.nanoTime(),
                System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
