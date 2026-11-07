package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityForkService;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.exam.application.ExamService;
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
 * Concurrency tests for the #47 fork (ADR-006 §8): eight same-key
 * racers collapse onto exactly one 201 (the UNIQUE(actor, key) loser
 * recovery returns the winner's target to every loser), different keys
 * fork independently, and the hide/unpublish/archive-vs-fork races
 * serialize on the §8 lock order (rate bucket → exam → publication)
 * into clean outcomes — a completed fork or a 404, never a partial
 * clone. Deliberately NOT {@code @Transactional} so every service call
 * runs in its own committed transaction
 * ({@code CommunityBookmarkConcurrencyIntegrationTests} pattern);
 * {@code @DirtiesContext} keeps the committed fixtures out of the
 * shared context. The FORK cap is raised so the idempotency mechanics,
 * not the §9 limiter, are what these racers exercise (the 5/h default
 * cap is covered by {@code CommunityForkRateLimitIntegrationTests}).
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.fork-limit=100"})
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityForkConcurrencyIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityForkService forkService;
    @Autowired
    private ExamService examService;

    private long publisherId;
    private long forkerId;
    private long sourceExamId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        publisherId = newUser("cfkt");
        forkerId = newUser("cfkf");
        sourceExamId = createPublishedExamWithComposition();
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, reuse_policy,
                                                    published_at)
                VALUES (%d, %d, 'Race publication', 'Cfk-Alias', 'PUBLISHED', 'VISIBLE',
                        'ALLOW_FORK', now())
                """.formatted(sourceExamId, publisherId));
    }

    // ==================================================================
    // Concurrent same-key: exactly one creation, everyone 2xx-equivalent
    // ==================================================================

    @Test
    void eightConcurrentSameKeySamePublicationYieldOneCreationOneRecordOneTarget() throws Exception {
        AtomicInteger created = new AtomicInteger();
        Callable<Long> task = () -> {
            CommunityForkService.ForkOutcome outcome =
                    forkService.fork(forkerId, publicationId, "race-key-same-0001");
            if (outcome.created()) {
                created.incrementAndGet();
            }
            return outcome.targetExamId();
        };
        List<Long> targets = runConcurrently(task, 8);

        assertThat(targets).as("all 8 racers succeeded").hasSize(8);
        assertThat(targets.stream().distinct().count())
                .as("every racer saw the SAME target exam")
                .isEqualTo(1);
        assertThat(created.get()).as("exactly one racer created").isEqualTo(1);
        assertThat(forkRecordCount()).isEqualTo(1);
        assertThat(forkTargetCount()).isEqualTo(1);
        assertThat(bankCount()).isEqualTo(1);
        assertThat(questionCountInForkBanks()).isEqualTo(4);
    }

    // ==================================================================
    // Concurrent different keys: two independent forks
    // ==================================================================

    @Test
    void concurrentDifferentKeysProduceTwoIndependentForks() throws Exception {
        Callable<Long> first = () -> forkService
                .fork(forkerId, publicationId, "race-key-alpha-01").targetExamId();
        Callable<Long> second = () -> forkService
                .fork(forkerId, publicationId, "race-key-beta-02").targetExamId();
        List<Long> targets = runMixed(first, second);

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0)).as("distinct targets").isNotEqualTo(targets.get(1));
        assertThat(forkRecordCount()).isEqualTo(2);
        assertThat(forkTargetCount()).isEqualTo(2);
        assertThat(bankCount()).isEqualTo(2);
    }

    // ==================================================================
    // hide-vs-fork / unpublish-vs-fork: 200-or-404, never partial
    // ==================================================================

    @Test
    void hideVsForkRaceEndsCompleteOrFailsWith404NeverPartial() throws Exception {
        Callable<String> forkTask = () -> {
            try {
                forkService.fork(forkerId, publicationId, "race-key-hide-001");
                return "FORKED";
            } catch (CommunityException ex) {
                return "EX:" + ex.getErrorCode().code();
            }
        };
        Callable<String> hideTask = () -> {
            jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                    publicationId);
            return "HIDDEN";
        };
        assertRaceOutcome(runMixed(forkTask, hideTask), "hide");
    }

    @Test
    void unpublishVsForkRaceEndsCompleteOrFailsWith404NeverPartial() throws Exception {
        Callable<String> forkTask = () -> {
            try {
                forkService.fork(forkerId, publicationId, "race-key-unpub-1");
                return "FORKED";
            } catch (CommunityException ex) {
                return "EX:" + ex.getErrorCode().code();
            }
        };
        Callable<String> unpublishTask = () -> {
            jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?",
                    publicationId);
            return "UNPUBLISHED";
        };
        assertRaceOutcome(runMixed(forkTask, unpublishTask), "unpublish");
    }

    // ==================================================================
    // archive-vs-fork: the exam row lock serializes them
    // ==================================================================

    @Test
    void archiveVsForkRaceSerializesOnTheExamLockIntoCleanOutcomes() throws Exception {
        Callable<String> forkTask = () -> {
            try {
                forkService.fork(forkerId, publicationId, "race-key-arch-001");
                return "FORKED";
            } catch (CommunityException ex) {
                return "EX:" + ex.getErrorCode().code();
            }
        };
        Callable<String> archiveTask = () -> {
            examService.archiveExam(publisherId, sourceExamId);
            return "ARCHIVED";
        };
        // No deadlock is itself an assertion: future.get would time out
        // (60s) if the §8 lock order (exam before publication) were
        // violated by either side.
        assertRaceOutcome(runMixed(forkTask, archiveTask), "archive");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM exams WHERE id = ?", String.class, sourceExamId))
                .isEqualTo("ARCHIVED");
    }

    /** A race outcome is legal iff the fork either completed FULLY or failed with a clean 404. */
    private void assertRaceOutcome(List<String> outcomes, String race) {
        // The racing writer always succeeds.
        assertThat(outcomes).containsAnyOf("HIDDEN", "UNPUBLISHED", "ARCHIVED");

        int records = forkRecordCount();
        assertThat(records).as("%s race: 0 or 1 fork records, never partial", race)
                .isLessThanOrEqualTo(1);
        if (records == 0) {
            // The fork must have failed cleanly with a 404 (visibility
            // recheck under the publication lock) and left NOTHING.
            assertThat(outcomes)
                    .as("the losing fork surfaced the anti-enumeration 404, not another error")
                    .contains("EX:COMMUNITY_NOT_FOUND");
            assertThat(forkTargetCount()).isZero();
            assertThat(bankCount()).as("no orphan bank on the failed %s race", race).isZero();
        } else {
            // The fork completed — and its target is COMPLETE: 4
            // questions, 12 options, a bank with 4 questions and 4
            // version-1 rows.
            assertThat(outcomes).as("the winning fork returned normally").contains("FORKED");
            Long targetExamId = jdbc.queryForObject(
                    "SELECT target_exam_id FROM community_fork_records WHERE actor_user_id = ?",
                    Long.class, forkerId);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM exam_questions WHERE exam_id = ?",
                    Integer.class, targetExamId)).isEqualTo(4);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM exam_question_options o
                    JOIN exam_questions q ON q.id = o.exam_question_id
                    WHERE q.exam_id = ?
                    """, Integer.class, targetExamId)).isEqualTo(12);
            assertThat(bankCount()).isEqualTo(1);
            assertThat(questionCountInForkBanks()).isEqualTo(4);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM question_versions v
                    JOIN questions q ON q.id = v.question_id
                    JOIN question_banks b ON b.id = q.question_bank_id
                    WHERE b.created_by_user_id = ? AND v.version_number = 1
                    """, Integer.class, forkerId)).isEqualTo(4);
        }
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private int forkRecordCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_fork_records WHERE actor_user_id = ?",
                Integer.class, forkerId);
    }

    private int forkTargetCount() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT target_exam_id) FROM community_fork_records WHERE actor_user_id = ?",
                Integer.class, forkerId);
    }

    private int bankCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM question_banks WHERE created_by_user_id = ?",
                Integer.class, forkerId);
    }

    private int questionCountInForkBanks() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM questions q
                JOIN question_banks b ON b.id = q.question_bank_id
                WHERE b.created_by_user_id = ?
                """, Integer.class, forkerId);
    }

    /** 2 sections × 2 questions (4 types) published, mirroring the happy-path fixture. */
    private long createPublishedExamWithComposition() {
        long bankId = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name, status)
                VALUES (%d, 'CFKB%s', 'Race source bank', 'ACTIVE')
                """.formatted(publisherId, System.nanoTime()));
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'CFKEX%s', 'Race source exam', 'PUBLISHED', now(), 10)
                """.formatted(publisherId, System.nanoTime()));
        long sectionId = insert("""
                INSERT INTO exam_sections (exam_id, title, position)
                VALUES (%d, 'Race section', 0)
                """.formatted(examId));

        int position = 0;
        for (String type : new String[]{"SINGLE_CHOICE", "MULTIPLE_CHOICE",
                "TRUE_FALSE_MATRIX", "NUMERIC_FILL"}) {
            long questionId = insert("""
                    INSERT INTO questions (question_bank_id, code, status, created_by)
                    VALUES (%d, 'CFKQ%s%d', 'ACTIVE', %d)
                    """.formatted(bankId, type.charAt(0), System.nanoTime(), publisherId));
            // NUMERIC_FILL must carry answer_key in the same INSERT
            // (chk_question_versions_answer_key_presence).
            long versionId = insert("""
                    INSERT INTO question_versions (question_id, version_number, question_type, content,
                                                   difficulty, default_points, answer_key, created_by)
                    VALUES (%d, 1, '%s', 'Race %s content', 'MEDIUM', 2.5, %s, %d)
                    """.formatted(questionId, type, type,
                            "NUMERIC_FILL".equals(type)
                                    ? "'{\"expectedAnswer\":\"1000\"}'::jsonb" : "NULL",
                            publisherId));
            if (!type.equals("NUMERIC_FILL")) {
                for (int i = 0; i < 4; i++) {
                    jdbc.update("""
                            INSERT INTO question_options (question_version_id, option_key, content,
                                                          is_correct, position)
                            VALUES (?, ?, ?, ?, ?)
                            """, versionId, String.valueOf((char) ('A' + i)),
                            "Race option " + (char) ('A' + i), i == 0, i);
                }
            }
            long eqId = insert("""
                    INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                                source_question_version_id, question_code, question_type,
                                                content, default_points, difficulty, answer_key, position)
                    VALUES (%d, %d, %d, %d, 'CFKEQ%s%d', '%s', 'Race %s content', 2.5, 'MEDIUM', %s, %d)
                    """.formatted(examId, sectionId, questionId, versionId,
                    type.charAt(0), System.nanoTime(), type, type,
                    "NUMERIC_FILL".equals(type)
                            ? "'{\"expectedAnswer\":\"1000\"}'::jsonb" : "NULL",
                    position++));
            if (!type.equals("NUMERIC_FILL")) {
                for (int i = 0; i < 4; i++) {
                    jdbc.update("""
                            INSERT INTO exam_question_options (exam_question_id, option_key, content,
                                                               is_correct, position)
                            VALUES (?, ?, ?, ?, ?)
                            """, eqId, String.valueOf((char) ('A' + i)),
                            "Race option " + (char) ('A' + i), i == 0, i);
                }
            }
        }
        return examId;
    }

    private long newUser(String prefix) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'CR', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, System.nanoTime(), prefix, System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                userId);
        return userId;
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
    private <T> List<T> runMixed(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(2);
        Future<T> f1 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return first.call();
        });
        Future<T> f2 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return second.call();
        });
        ready.await();
        start.countDown();
        List<T> results = List.of(f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
