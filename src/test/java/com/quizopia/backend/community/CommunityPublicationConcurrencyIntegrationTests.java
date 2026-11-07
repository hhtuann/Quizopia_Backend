package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityService;
import com.quizopia.backend.community.dto.CreatePublicationRequest;
import com.quizopia.backend.community.dto.UpdatePublicationRequest;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for the #40 owner lifecycle (ADR-006 §4/§8):
 * duplicate create races resolve via the UNIQUE(exam_id) constraint,
 * concurrent PATCHes resolve via the optimistic {@code version} token
 * (exactly one winner), and concurrent publish/unpublish pairs serialize
 * on the publication row lock with idempotent no-ops for the loser.
 *
 * <p>Deliberately NOT {@code @Transactional} so every service call runs
 * in its own committed transaction and the pessimistic row lock is
 * exercised for real ({@code ExamPublishConcurrencyIntegrationTests}
 * pattern); {@code @DirtiesContext} keeps the committed fixtures out of
 * the shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityPublicationConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityService communityService;

    private long teacherId;

    @BeforeEach
    void setUp() {
        teacherId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('cxc%s', 'cxc%s@t.example.com', 'h', 'CX', 'ACTIVE', now(), 0, 0, now())
                """.formatted(System.nanoTime(), System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                teacherId);
    }

    @Test
    void concurrentDuplicateCreateYieldsExactlyOne201AndOneConflict() throws Exception {
        long examId = newPublishedExam();

        Callable<String> task = () -> {
            try {
                communityService.createPublication(teacherId,
                        new CreatePublicationRequest(examId, "Race quiz", null, null, null, null, null));
                return "OK";
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };
        List<String> results = runConcurrently(task, 2);

        assertThat(results).containsExactlyInAnyOrder("OK", CommunityErrorCode.COMMUNITY_PUBLICATION_EXISTS.code());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_publications WHERE exam_id = ?", Integer.class, examId))
                .isEqualTo(1);
    }

    @Test
    void concurrentPatchesWithSameExpectedVersionYieldOneWinner() throws Exception {
        long publicationId = createPublication();

        Callable<String> task = () -> {
            try {
                communityService.updatePublication(teacherId, publicationId,
                        new UpdatePublicationRequest(0, "winner title", null, null, null, null, null));
                return "OK";
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };
        List<String> results = runConcurrently(task, 2);

        assertThat(results).containsExactlyInAnyOrder("OK", CommunityErrorCode.COMMUNITY_CONCURRENT_MODIFICATION.code());
        assertThat(titleAndVersion(publicationId)).isEqualTo("winner title/1");
    }

    @Test
    void concurrentPublishPublishBothSucceedWithSingleTransition() throws Exception {
        long publicationId = createAliasedPublication();

        Callable<String> task = () -> {
            try {
                communityService.publishPublication(teacherId, publicationId);
                return "OK";
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };
        List<String> results = runConcurrently(task, 2);

        // One real transition + one idempotent no-op: both 200.
        assertThat(results).containsExactly("OK", "OK");
        assertThat(stateAndVersion(publicationId))
                .isEqualTo("PUBLISHED/2"); // create(0) → alias PATCH(1) → publish(2)
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM community_publications WHERE id = ?",
                java.sql.Timestamp.class, publicationId)).isNotNull();
    }

    @Test
    void concurrentPublishUnpublishSerializeOnTheRowLock() throws Exception {
        long publicationId = createAliasedPublication();

        Callable<String> publish = () -> {
            communityService.publishPublication(teacherId, publicationId);
            return "PUBLISHED";
        };
        Callable<String> unpublish = () -> {
            communityService.unpublishPublication(teacherId, publicationId);
            return "UNPUBLISHED";
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // Interleaving control: both threads race for the same row lock.
        CountDownLatch ready = new CountDownLatch(2);
        Callable<String> publishing = () -> {
            ready.countDown();
            start.await();
            return publish.call();
        };
        Callable<String> unpublishing = () -> {
            ready.countDown();
            start.await();
            return unpublish.call();
        };
        var f1 = pool.submit(publishing);
        var f2 = pool.submit(unpublishing);
        ready.await();
        start.countDown();
        List<String> results = List.of(f1.get(30, java.util.concurrent.TimeUnit.SECONDS),
                f2.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Both requests succeed; the row lock serialized them. Exactly one
        // or two real transitions happened depending on lock order, and the
        // final state matches the LAST transition.
        assertThat(results).containsExactlyInAnyOrder("PUBLISHED", "UNPUBLISHED");
        String state = stateAndVersion(publicationId);
        if (state.endsWith("/2")) {
            // unpublish no-op first, publish transition second.
            assertThat(state).isEqualTo("PUBLISHED/2");
        } else {
            // publish transition then unpublish transition.
            assertThat(state).isEqualTo("UNPUBLISHED/3");
        }
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private long newPublishedExam() {
        return insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'CXE%s', 'CX exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
    }

    private long createPublication() {
        return communityService
                .createPublication(teacherId, new CreatePublicationRequest(
                        newPublishedExam(), "CX quiz", null, null, null, null, null))
                .publicationId();
    }

    private long createAliasedPublication() {
        long id = createPublication();
        communityService.updatePublication(teacherId, id,
                new UpdatePublicationRequest(0, null, null, null, null, "Race Author", null));
        return id;
    }

    private List<String> runConcurrently(Callable<String> task, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<String> results = new java.util.ArrayList<>();
        for (var future : futures) {
            results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private String stateAndVersion(long publicationId) {
        String status = jdbc.queryForObject(
                "SELECT status FROM community_publications WHERE id = ?", String.class, publicationId);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM community_publications WHERE id = ?", Integer.class, publicationId);
        return status + "/" + version;
    }

    private String titleAndVersion(long publicationId) {
        String title = jdbc.queryForObject(
                "SELECT title FROM community_publications WHERE id = ?", String.class, publicationId);
        Integer version = jdbc.queryForObject(
                "SELECT version FROM community_publications WHERE id = ?", Integer.class, publicationId);
        return title + "/" + version;
    }
}
