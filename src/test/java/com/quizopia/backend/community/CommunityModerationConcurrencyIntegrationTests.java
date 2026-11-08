package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityReportService;
import com.quizopia.backend.community.application.CommunityModerationService;
import com.quizopia.backend.community.dto.ReportPublicationRequest;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for #42 (ADR-006 §7/§4, issue #42):
 *
 * <ul>
 *   <li><b>reporter dedupe race</b> — concurrent same-reporter
 *       submissions for one publication collapse to exactly ONE OPEN
 *       report via {@code INSERT … ON CONFLICT … DO NOTHING} +
 *       re-read: one 201 + one 200 (same report id), never two OPEN.</li>
 *   <li><b>hide vs restore race</b> — both transitions take ONLY the
 *       publication row lock, so concurrent hide/restore serialize:
 *       consistent final state, one or two real transitions, and an
 *       audit row per real transition (repeat no-op writes none).</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional} so every service call runs
 * in its own committed transaction and the pessimistic row locks are
 * exercised for real ({@code CommunityPublicationConcurrencyIntegrationTests}
 * pattern); {@code @DirtiesContext} keeps the committed fixtures out of
 * the shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityModerationConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityReportService reportService;
    @Autowired
    private CommunityModerationService moderationService;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long moderatorId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("mcc");
        moderatorId = newUser("mcm");
        grantModerator(moderatorId);
    }

    // ==================================================================
    // reporter dedupe race (§7 step 3)
    // ==================================================================

    @Test
    void concurrentSameReporterSubmissionsYieldExactlyOneOpenReport() throws Exception {
        long publicationId = createPublishedPublication();
        long reporterId = newUser("mcr");

        Callable<String> task = () -> {
            try {
                CommunityReportService.SubmittedReport submitted = reportService.submitReport(
                        reporterId, publicationId,
                        new ReportPublicationRequest("SPAM", "race"), "10.0.0.1");
                return submitted.created() ? "CREATED" : "DEDUPED";
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };

        List<String> results = runConcurrently(task, 2);

        // One real insert (201) and one dedupe (200) — same report id,
        // never two OPEN rows.
        assertThat(results).containsExactlyInAnyOrder("CREATED", "DEDUPED");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_reports
                        WHERE publication_id = ? AND reporter_user_id = ? AND status = 'OPEN'
                        """, Integer.class, publicationId, reporterId))
                .as("exactly one OPEN report survives the race")
                .isEqualTo(1);
    }

    @Test
    void manyWaySameReporterRaceStillYieldsExactlyOneOpenReport() throws Exception {
        long publicationId = createPublishedPublication();
        long reporterId = newUser("mcr");

        Callable<String> task = () -> {
            try {
                return reportService.submitReport(reporterId, publicationId,
                        new ReportPublicationRequest("OTHER", null), "10.0.0.2").created()
                        ? "CREATED" : "DEDUPED";
            } catch (CommunityException ex) {
                return ex.getErrorCode().code();
            }
        };

        List<String> results = runConcurrently(task, 4);

        assertThat(results).containsOnlyOnce("CREATED");
        assertThat(results).doesNotContain(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_reports
                        WHERE publication_id = ? AND reporter_user_id = ?
                        """, Integer.class, publicationId, reporterId))
                .isEqualTo(1);
    }

    // ==================================================================
    // hide vs restore race (§4)
    // ==================================================================

    @Test
    void concurrentHideVsRestoreSerializesOnThePublicationRowLock() throws Exception {
        long publicationId = createPublishedPublication();

        AtomicReference<String> hideError = new AtomicReference<>();
        AtomicReference<String> restoreError = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> hiding = pool.submit(() -> {
            try {
                start.await();
                moderationService.hide(moderatorId, publicationId, "race hide");
            } catch (Throwable ex) {
                hideError.set(String.valueOf(ex));
            }
            return null;
        });
        Future<?> restoring = pool.submit(() -> {
            try {
                start.await();
                moderationService.restore(moderatorId, publicationId, "race restore");
            } catch (Throwable ex) {
                restoreError.set(String.valueOf(ex));
            }
            return null;
        });
        start.countDown();
        hiding.get(30, TimeUnit.SECONDS);
        restoring.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(hideError.get()).as("hide must not fail").isNull();
        assertThat(restoreError.get()).as("restore must not fail").isNull();

        // The row lock serialized the pair: exactly one or two real
        // transitions (start state VISIBLE → hide transitions; start
        // HIDDEN → restore transitions; the second call may be an
        // idempotent no-op). Final state is consistent with the LAST
        // transition and every real transition has an audit row.
        String finalState = moderationState(publicationId);
        assertThat(finalState).isIn("VISIBLE", "HIDDEN");
        int auditCount = auditCount(publicationId);
        assertThat(auditCount).isIn(1, 2);

        // Audit rows alternate correctly and the last row's
        // to_moderation_state equals the final state.
        List<String> audit = jdbc.queryForList("""
                        SELECT a.action || '>' || a.to_moderation_state
                        FROM community_moderation_actions a
                        WHERE a.publication_id = ? ORDER BY a.id
                        """, String.class, publicationId);
        if (audit.size() == 1) {
            // hide no-op'd first then restore... impossible from
            // VISIBLE; so the single row is HIDE, or RESTORE won after
            // a hide that no-op'd — both covered by the final-state
            // check below.
            assertThat(audit.get(0)).endsWith(finalState);
        } else {
            assertThat(audit).containsExactly("HIDE>HIDDEN", "RESTORE>VISIBLE");
            assertThat(finalState).isEqualTo("VISIBLE");
        }
        // Status is never touched by moderation.
        assertThat(statusOf(publicationId)).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentDuplicateHidesAppendExactlyOneAuditRow() throws Exception {
        long publicationId = createPublishedPublication();

        Callable<String> task = () -> {
            moderationService.hide(moderatorId, publicationId, "dup hide");
            return "OK";
        };
        List<String> results = runConcurrently(task, 2);

        assertThat(results).containsExactly("OK", "OK");
        assertThat(moderationState(publicationId)).isEqualTo("HIDDEN");
        assertThat(auditCount(publicationId))
                .as("one real transition + one idempotent no-op = one audit row")
                .isEqualTo(1);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    /** A PUBLISHED + VISIBLE publication (§4 predicate passes). */
    private long createPublishedPublication() {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'MCE%s', 'Concurrency exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Concurrency publication', 'Race-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    private long newUser(String prefix) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'MCC', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                userId);
        return userId;
    }

    private void grantModerator(long userId) {
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'COMMUNITY_MODERATOR'
                """, userId);
    }

    private String moderationState(long publicationId) {
        return jdbc.queryForObject(
                "SELECT moderation_state FROM community_publications WHERE id = ?",
                String.class, publicationId);
    }

    private String statusOf(long publicationId) {
        return jdbc.queryForObject(
                "SELECT status FROM community_publications WHERE id = ?",
                String.class, publicationId);
    }

    private int auditCount(long publicationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM community_moderation_actions WHERE publication_id = ?",
                Integer.class, publicationId);
        return count == null ? 0 : count;
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

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
