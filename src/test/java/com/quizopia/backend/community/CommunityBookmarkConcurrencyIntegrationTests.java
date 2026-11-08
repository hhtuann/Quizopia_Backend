package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityBookmarkService;
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
 * Concurrency tests for the #45 bookmark shelf (ADR-006 §7): duplicate
 * concurrent saves collapse onto exactly one row through
 * {@code INSERT … ON CONFLICT (user_id, publication_id) DO NOTHING} —
 * every racer gets 2xx, exactly one 201 — and concurrent PUT/DELETE
 * pairs leave a deterministic per-finish-order state with no constraint
 * violations.
 *
 * <p>Deliberately NOT {@code @Transactional} so every service call runs
 * in its own committed transaction and the native upsert races are
 * exercised for real ({@code CommunityPublicationConcurrencyIntegrationTests}
 * pattern); {@code @DirtiesContext} keeps the committed fixtures out of
 * the shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityBookmarkConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityBookmarkService bookmarkService;

    private long studentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        long teacherId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('cbct%s', 'cbct%s@t.example.com', 'h', 'CT', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                teacherId);
        studentId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('cbcs%s', 'cbcs%s@t.example.com', 'h', 'CS', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                studentId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'CBE%s', 'Race exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Race publication', 'Race-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    @Test
    void eightConcurrentSavesYieldExactlyOneRowAll2xxOne201() throws Exception {
        AtomicInteger createdCount = new AtomicInteger();
        Callable<String> task = () -> {
            CommunityBookmarkService.SavedBookmark saved =
                    bookmarkService.saveBookmark(studentId, publicationId);
            if (saved.created()) {
                createdCount.incrementAndGet();
            }
            return saved.savedAt().toString();
        };
        List<String> savedAts = runConcurrently(task, 8);

        // All 8 racers succeeded and every one saw the SAME row.
        assertThat(savedAts).hasSize(8);
        assertThat(savedAts.stream().distinct().count()).as("one row, one savedAt").isEqualTo(1);
        assertThat(createdCount.get()).as("exactly one racer created the row").isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void concurrentSaveDeletePairsLeaveNoViolationsAndADeterministicEndState() throws Exception {
        // Several rounds of full-barrier PUT+DELETE pairs: both sides
        // always succeed (2xx semantics at service level), the row
        // state after each round matches the LAST committed mutation,
        // and no constraint violation ever surfaces.
        for (int round = 0; round < 5; round++) {
            Callable<String> save = () -> {
                bookmarkService.saveBookmark(studentId, publicationId);
                return "PUT";
            };
            Callable<String> delete = () -> {
                bookmarkService.deleteBookmark(studentId, publicationId);
                return "DELETE";
            };
            List<String> results = runMixed(save, delete);
            assertThat(results).containsExactlyInAnyOrder("PUT", "DELETE");
            assertThat(rowCount())
                    .as("row state is 0 or 1 — never a duplicate, never an error")
                    .isLessThanOrEqualTo(1);

            // Re-establish a known state for the next round.
            bookmarkService.deleteBookmark(studentId, publicationId);
            assertThat(rowCount()).isZero();
        }
    }

    @Test
    void finishOrderDeterminesTheRowState() throws Exception {
        // Sequential determinism: PUT-then-DELETE leaves no row;
        // DELETE-then-PUT leaves exactly one.
        bookmarkService.saveBookmark(studentId, publicationId);
        bookmarkService.deleteBookmark(studentId, publicationId);
        assertThat(rowCount()).as("save then delete → absent").isZero();

        bookmarkService.deleteBookmark(studentId, publicationId);
        assertThat(bookmarkService.saveBookmark(studentId, publicationId).created())
                .as("delete (absent, 200-by-shape) then save → first-save semantics")
                .isTrue();
        assertThat(rowCount()).as("delete then save → present").isEqualTo(1);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private int rowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_bookmarks WHERE user_id = ? AND publication_id = ?",
                Integer.class, studentId, publicationId);
    }

    private List<String> runConcurrently(Callable<String> task, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<String> results = new java.util.ArrayList<>();
        for (Future<String> future : futures) {
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

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
