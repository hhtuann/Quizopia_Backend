package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewResponse;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F01 regression: REAL concurrent PostgreSQL transactions against the
 * review append path.
 *
 * <p>NOT {@code @Transactional} — every {@code review()} call opens its own
 * transaction, so two racing calls exercise genuine constraint conflicts on
 * {@code uk_alert_reviews_version} / {@code uk_alert_reviews_idem} and the
 * post-conflict winner probe must run OUTSIDE the aborted transaction
 * (PostgreSQL aborts a tx after a constraint violation — the historical bug
 * probed inside it and 500'd).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
class AlertReviewConcurrencyIntegrationTests {

    @Autowired private AlertReviewService reviewService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long teacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private long alertId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-17T14:00:00Z");
        clock.setInstant(base);
        teacherId = newTeacherUser(jdbc, "conc-teacher-" + UUID.randomUUID().toString().substring(0, 8));
        studentId = newUser(jdbc, "conc-student-" + UUID.randomUUID().toString().substring(0, 8));
        sessionId = insertOpenSession(jdbc, teacherId, base);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", studentId, sessionId, teacherId);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
        jdbc.update("INSERT INTO monitoring_ai_alerts "
                + "(attempt_id, exam_session_id, user_id, alert_type, status, "
                + "first_detected_at, last_detected_at, detection_count, model_id, "
                + "model_version, provenance) "
                + "VALUES (?,?,?,'PHONE_DETECTED','OPEN',?,?,1,'yolo26s','8.4.64-onnx',"
                + "'CLIENT_MODEL')",
                attemptId, sessionId, studentId,
                java.sql.Timestamp.from(base), java.sql.Timestamp.from(base));
        alertId = jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?", Long.class, attemptId);
    }

    private record Outcome(AlertReviewResponse response, Throwable error) {
    }

    private Outcome race(CountDownLatch start, String action, String note, UUID key) {
        try {
            AlertReviewResponse r = reviewService.review(teacherId, alertId, 0, action, note, key);
            return new Outcome(r, null);
        } catch (Throwable t) {
            return new Outcome(null, t);
        }
    }

    @Test
    void twoRacingDifferentKeysExactlyOneAppendAndOneConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> a = pool.submit(() -> race(start, "MARK_VIEWED", null, UUID.randomUUID()));
        Future<Outcome> b = pool.submit(() -> race(start, "FALSE_POSITIVE", null, UUID.randomUUID()));
        start.countDown();
        List<Outcome> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        long conflicts = outcomes.stream()
                .filter(o -> o.error() instanceof MonitoringException me
                        && me.getErrorCode() == MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT)
                .count();
        // Exactly one ledger row; loser got a CONFLICT (409), never a 500.
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.error())
                .satisfiesAnyOf(
                        t -> assertThat(t).isNull(),
                        t -> assertThat(t).isInstanceOf(MonitoringException.class)));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId);
        assertThat(rows).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT review_version FROM monitoring_ai_alerts WHERE id = ?",
                Integer.class, alertId)).isEqualTo(1);
    }

    @Test
    void twoRacingSameKeyReplayOrConflictNever500AndOneRow() throws Exception {
        UUID key = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Outcome> a = pool.submit(() -> race(start, "NOTED", "same", key));
        Future<Outcome> b = pool.submit(() -> race(start, "NOTED", "same", key));
        start.countDown();
        List<Outcome> outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        // The loser either replays the committed winner (probe found it) or
        // gets a version conflict (winner not yet committed) — the historical
        // bug 500'd here because the probe ran on the aborted transaction.
        for (Outcome o : outcomes) {
            if (o.error() != null) {
                assertThat(o.error()).isInstanceOf(MonitoringException.class);
                MonitoringException me = (MonitoringException) o.error();
                assertThat(me.getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT);
            } else if (!o.response().idempotentReplay()) {
                assertThat(o.response().reviewVersion()).isEqualTo(1);
            }
        }
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void replayAfterLaterReviewStillReturnsStoredVersionOne() {
        UUID key = UUID.randomUUID();
        reviewService.review(teacherId, alertId, 0, "MARK_VIEWED", null, key);
        reviewService.review(teacherId, alertId, 1, "NOTED", "later", UUID.randomUUID());
        AlertReviewResponse replay = reviewService.review(teacherId, alertId, 0,
                "MARK_VIEWED", null, key);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.reviewVersion()).isEqualTo(1);
        assertThat(replay.reviewStatus()).isEqualTo("VIEWED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId)).isEqualTo(2L);
    }

    @Test
    void fiveWayRaceProducesContiguousVersionsWithNoGapsOr500s() throws Exception {
        // Sequential-style races on successive versions are covered above;
        // here five DIFFERENT-key reviews race the SAME stale version — one
        // wins, four get 409; a retry loop then drains versions 1..5.
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() -> race(start, "MARK_VIEWED", null, UUID.randomUUID())));
        }
        start.countDown();
        int conflicts = 0;
        int successes = 0;
        for (Future<Outcome> f : futures) {
            Outcome o = f.get(30, TimeUnit.SECONDS);
            if (o.error() == null) {
                successes++;
            } else {
                assertThat(o.error()).isInstanceOf(MonitoringException.class);
                conflicts++;
            }
        }
        pool.shutdownNow();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(4);

        // Drain: each retry uses the fresh current version.
        AtomicReference<Integer> current = new AtomicReference<>(1);
        for (int i = 0; i < 4; i++) {
            AlertReviewResponse r = reviewService.review(teacherId, alertId, current.get(),
                    "NOTED", null, UUID.randomUUID());
            assertThat(r.reviewVersion()).isEqualTo(current.get() + 1);
            current.set(r.reviewVersion());
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId)).isEqualTo(5L);
    }
}
