package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.finishAttempt;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for the P4-C AI detection ingest core (issues
 * #88/#90, CONTRACT §3/§5/§7) — the full backend matrix:
 *
 * <ul>
 *   <li>auth: AI_DETECTION_INGEST permission, attempt ownership
 *       (404/403), batch bound, rate limit 429 (own
 *       monitoring:ai-ingest domain);</li>
 *   <li>idempotency: duplicate eventId → DUPLICATE, never a second
 *       alert;</li>
 *   <li>time binding &amp; quarantine (§3.2): finished attempt →
 *       QUARANTINED_LATE stored with reason (never aggregates);
 *       observedAt outside [startedAt−5s, now+5s] → QUARANTINED_LATE with
 *       lateMs; no active media binding → REJECTED NO_ACTIVE_MEDIA (not
 *       stored);</li>
 *   <li>aggregation (§5): 10 detections within the cooldown → ONE alert
 *       count=10; AI_ALERT_UPDATED only at counts {2,4,8}; gap &gt;
 *       cooldown → second alert row (first window CLOSED, keeps its
 *       evidence); PERSON_NOT_VISIBLE uses the 45s cooldown; quarantined
 *       detections never aggregate;</li>
 *   <li>honesty (§7/§8): the full ingest flow writes NOTHING to the
 *       grading domain (grades/grade_items stay empty).</li>
 * </ul>
 *
 * <p>Rate-limit saturation runs in a {@link Nested} class with a
 * dedicated context ({@code rate-limit=3}) so the outer tests keep the
 * production-shaped default window.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AiDetectionIngestIntegrationTests {

    @Autowired private AiDetectionIngestService ingestService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long studentId;
    private long sessionId;
    private long attemptId;

    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T08:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "aistudent");
        sessionId = insertOpenSession(jdbc, studentId, base);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        // Active media binding for (attempt, user) — the P4-B table the
        // ingest gate requires (issue #88 media-session-to-Attempt
        // validation). Inserted AFTER start so the P4-B start gate never
        // interferes (the established fixture convention).
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
    }

    // === happy path ===

    @Test
    void happyPathStoresDetectionRaisesAlertAndRecordsServerActivityEvent() {
        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.minusSeconds(1), 1, 0.87)));

        assertThat(r.acceptedCount()).isEqualTo(1);
        assertThat(r.results().get(0).status())
                .isEqualTo(AiDetectionBatchResponse.Status.ACCEPTED);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT provenance, event_type, quarantined, quarantine_reason, model_id, "
                        + "model_version, runtime, payload FROM monitoring_ai_detections WHERE event_id=?",
                eventIdOf(r, 0));
        assertThat(row.get("provenance")).isEqualTo("CLIENT_MODEL");
        assertThat(row.get("event_type")).isEqualTo("PHONE_OBSERVED");
        assertThat(row.get("quarantined")).isEqualTo(Boolean.FALSE);
        assertThat(row.get("quarantine_reason")).isNull();
        assertThat(row.get("model_id")).isEqualTo("yolo26s");
        assertThat(row.get("model_version")).isEqualTo("8.4.64-onnx");
        assertThat(row.get("runtime")).isEqualTo("ORT_WEB_WEBGPU");
        assertThat(row.get("payload").toString()).contains("cell phone");

        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT alert_type, status, detection_count, max_confidence, model_id, provenance "
                        + "FROM monitoring_ai_alerts WHERE attempt_id=?", attemptId);
        assertThat(alert.get("alert_type")).isEqualTo("PHONE_DETECTED");
        assertThat(alert.get("status")).isEqualTo("OPEN");
        assertThat(alert.get("detection_count")).isEqualTo(1);
        assertThat((Double) alert.get("max_confidence")).isEqualTo(0.87);
        assertThat(alert.get("provenance")).isEqualTo("CLIENT_MODEL");

        // AI_ALERT_RAISED rides the EXISTING activity-event path (source
        // SERVER; STOMP ping happens AFTER_COMMIT — no new destination).
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT source, metadata FROM attempt_activity_events "
                        + "WHERE attempt_id=? AND event_type='AI_ALERT_RAISED'", attemptId);
        assertThat(event.get("source")).isEqualTo("SERVER");
        JsonNode metadata = objectMapper.readTree(event.get("metadata").toString());
        assertThat(metadata.get("alertId").asLong()).isPositive();
        assertThat(metadata.get("alertType").asString()).isEqualTo("PHONE_DETECTED");
        assertThat(metadata.get("provenance").asString()).isEqualTo("CLIENT_MODEL");
        assertThat(metadata.get("modelVersion").asString()).isEqualTo("8.4.64-onnx");
        assertThat(metadata.get("modelId").asString()).isEqualTo("yolo26s");
        assertThat(metadata.get("detectionCount").asInt()).isEqualTo(1);
        assertThat(metadata.get("maxConfidence").asDouble()).isEqualTo(0.87);
        assertThat(metadata.get("detectionEventId").asString())
                .isEqualTo(r.results().get(0).eventId().toString());
    }

    // === idempotency ===

    @Test
    void duplicateEventIdIsDuplicateAndNeverAlertsTwice() {
        AiDetectionBatchRequest.Item shared = phoneDetection(base, 1, 0.9);
        AiDetectionBatchResponse first = ingestService.ingestStudent(studentId, attemptId,
                batch(shared, phoneDetection(base.plusMillis(200), 2, 0.7)));
        assertThat(first.acceptedCount()).isEqualTo(2);

        AiDetectionBatchResponse retry = ingestService.ingestStudent(studentId, attemptId,
                batch(shared));
        assertThat(retry.acceptedCount()).isZero();
        assertThat(retry.results().get(0).status())
                .isEqualTo(AiDetectionBatchResponse.Status.DUPLICATE);

        assertThat(detectionCount()).isEqualTo(2); // zero new rows
        assertThat(alertCount()).isEqualTo(1);     // ONE window, extended
        assertThat(activityCount("AI_ALERT_RAISED")).isEqualTo(1); // never re-raised
    }

    // === authorization ===

    @Test
    void foreignAttemptRejected403() {
        long other = newUser(jdbc, "aiforeign");
        assertThatThrownBy(() -> ingestService.ingestStudent(other, attemptId,
                batch(phoneDetection(base, 1, 0.5))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test
    void missingAttemptRejected404() {
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, 999999L,
                batch(phoneDetection(base, 1, 0.5))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ATTEMPT_NOT_FOUND"));
    }

    @Test
    void revokedIngestPermissionRejected403() {
        revoke("AI_DETECTION_INGEST");
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base, 1, 0.5))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void unknownEventTypeRejected400() {
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId,
                batch(item("PHONE_DETECTED", base, 1)))) // DETECTED is server vocabulary
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_EVENT_TYPE_REJECTED"));
        assertThat(detectionCount()).isZero();
    }

    @Test
    void workerRuntimeFromStudentPathRejected400() {
        AiDetectionBatchRequest.Item workerRuntime = new AiDetectionBatchRequest.Item(
                UUID.randomUUID(), "PHONE_OBSERVED", base, 1L, "yolo26s", "8.4.64-onnx",
                "WORKER_ORT", 640, 480, 55, List.of());
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId,
                batch(workerRuntime)))
                .as("a browser path can never claim the worker runtime (structural provenance)")
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_VALIDATION_ERROR"));
    }

    // === bounds ===

    @Test
    void batchOfNineRejected400BeforeAnyInsert() {
        List<AiDetectionBatchRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            items.add(phoneDetection(base, i, 0.5));
        }
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId,
                new AiDetectionBatchRequest(items)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_BATCH_TOO_LARGE"));
        assertThat(detectionCount()).isZero();
    }

    @Test
    void year2500ObservedAtRejected400() {
        AiDetectionBatchRequest.Item future = new AiDetectionBatchRequest.Item(
                UUID.randomUUID(), "PHONE_OBSERVED", Instant.parse("2500-01-01T00:00:00Z"),
                1L, "yolo26s", "8.4.64-onnx", "ORT_WEB_WASM", 640, 480, 50, List.of());
        assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId, batch(future)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_VALIDATION_ERROR"));
        assertThat(detectionCount()).isZero();
    }

    // === media binding gate (§3.2 / issue #88) ===

    @Test
    void noActiveMediaBindingRejectedNoActiveMediaAndNotStored() {
        jdbc.update("UPDATE monitoring_media_bindings SET active_to=? WHERE attempt_id=?",
                java.sql.Timestamp.from(base), attemptId);

        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base, 1, 0.9)));
        assertThat(r.acceptedCount()).isZero();
        assertThat(r.rejectedCount()).isEqualTo(1);
        assertThat(r.results().get(0).status())
                .isEqualTo(AiDetectionBatchResponse.Status.REJECTED);
        assertThat(r.results().get(0).reason()).isEqualTo("NO_ACTIVE_MEDIA");
        assertThat(detectionCount()).isZero(); // NOT stored
        assertThat(alertCount()).isZero();
    }

    // === time binding & quarantine (§3.2) ===

    @Test
    void finishedAttemptQuarantinedStoredNeverAlerts() {
        finishAttempt(jdbc, attemptId, base);

        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base, 1, 0.9)));
        assertThat(r.quarantinedCount()).isEqualTo(1);
        assertThat(r.results().get(0).status())
                .isEqualTo(AiDetectionBatchResponse.Status.QUARANTINED_LATE);
        assertThat(r.results().get(0).reason()).isEqualTo("ATTEMPT_NOT_IN_PROGRESS");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT quarantined, quarantine_reason FROM monitoring_ai_detections");
        assertThat(row.get("quarantined")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("quarantine_reason")).isEqualTo("ATTEMPT_NOT_IN_PROGRESS");

        assertThat(alertCount()).as("quarantined detections NEVER aggregate").isZero();
        assertThat(activityCount("AI_ALERT_RAISED")).isZero();
    }

    @Test
    void observedAtBeforeAttemptWindowQuarantinedWithLateMs() {
        Instant tooEarly = base.minusSeconds(10); // window starts at startedAt-5s
        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(tooEarly, 1, 0.9)));
        assertThat(r.quarantinedCount()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT quarantined, quarantine_reason, late_ms FROM monitoring_ai_detections");
        assertThat(row.get("quarantined")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("quarantine_reason")).isEqualTo("OBSERVED_AT_OUT_OF_WINDOW");
        assertThat((Long) row.get("late_ms")).isEqualTo(5000L);
        assertThat(jdbc.queryForObject("SELECT observed_at FROM monitoring_ai_detections",
                Instant.class)).isEqualTo(tooEarly);
        assertThat(alertCount()).isZero();
    }

    @Test
    void observedAtAfterNowPlusSlackQuarantined() {
        Instant tooLate = base.plusSeconds(60); // window ends at now+5s
        AiDetectionBatchResponse r = ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(tooLate, 1, 0.9)));
        assertThat(r.quarantinedCount()).isEqualTo(1);
        Long lateMs = jdbc.queryForObject(
                "SELECT late_ms FROM monitoring_ai_detections", Long.class);
        assertThat(lateMs).isEqualTo(55000L);
        assertThat(alertCount()).isZero();
    }

    @Test
    void lateDetectionStillBindsOriginalAttempt() {
        finishAttempt(jdbc, attemptId, base);
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.minusSeconds(30), 1, 0.9)));
        Long boundAttempt = jdbc.queryForObject(
                "SELECT attempt_id FROM monitoring_ai_detections", Long.class);
        assertThat(boundAttempt).as("late/out-of-window events bind the ORIGINAL attempt")
                .isEqualTo(attemptId);
    }

    // === aggregation (§5) ===

    @Test
    void tenDetectionsWithinCooldownAggregateIntoOneAlertCountTen() {
        // batch 1: 5 events at base+0..4s; batch 2: 5 events at base+5..9s
        List<AiDetectionBatchRequest.Item> first = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            first.add(phoneDetection(base.plusSeconds(i), i, 0.5 + i * 0.05));
        }
        List<AiDetectionBatchRequest.Item> second = new ArrayList<>();
        for (int i = 5; i < 10; i++) {
            second.add(phoneDetection(base.plusSeconds(i), i, 0.5 + i * 0.01));
        }
        ingestService.ingestStudent(studentId, attemptId, new AiDetectionBatchRequest(first));
        // The server clock must cover the second batch's observedAt
        // window ([startedAt-5s, now+5s]) — 10 detections spread over 10s.
        clock.advance(Duration.ofSeconds(10));
        ingestService.ingestStudent(studentId, attemptId, new AiDetectionBatchRequest(second));

        assertThat(detectionCount()).isEqualTo(10);
        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT detection_count, max_confidence, first_detected_at, last_detected_at, "
                        + "latest_payload FROM monitoring_ai_alerts WHERE attempt_id=?", attemptId);
        assertThat(alert.get("detection_count")).isEqualTo(10);
        // max of 0.5..0.7 (batch1) and 0.55..0.59 (batch2) → 0.7
        assertThat((Double) alert.get("max_confidence")).isEqualTo(0.7);
        assertThat(jdbc.queryForObject("SELECT first_detected_at FROM monitoring_ai_alerts "
                + "WHERE attempt_id=?", Instant.class, attemptId)).isEqualTo(base);
        assertThat(jdbc.queryForObject("SELECT last_detected_at FROM monitoring_ai_alerts "
                + "WHERE attempt_id=?", Instant.class, attemptId))
                .isEqualTo(base.plusSeconds(9));
        assertThat(alert.get("latest_payload").toString()).contains("cell phone");

        // notification cadence: RAISED once + UPDATED at counts 2,4,8 ONLY
        assertThat(activityCount("AI_ALERT_RAISED")).isEqualTo(1);
        assertThat(activityCount("AI_ALERT_UPDATED")).isEqualTo(3);
    }

    @Test
    void gapBeyondCooldownClosesWindowAndOpensSecondAlertRow() {
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base, 1, 0.9)));

        clock.advance(Duration.ofSeconds(30)); // gap 30s > 20s cooldown
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.plusSeconds(30), 2, 0.6)));

        List<Map<String, Object>> windows = jdbc.queryForList(
                "SELECT id, status, detection_count FROM monitoring_ai_alerts "
                        + "WHERE attempt_id=? ORDER BY id", attemptId);
        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).get("status")).as("old window keeps its evidence, CLOSED")
                .isEqualTo("CLOSED");
        assertThat(((Number) windows.get(0).get("detection_count")).intValue()).isEqualTo(1);
        assertThat(windows.get(1).get("status")).isEqualTo("OPEN");
        Long secondId = ((Number) windows.get(1).get("id")).longValue();
        assertThat(jdbc.queryForObject(
                "SELECT first_detected_at FROM monitoring_ai_alerts WHERE id=?",
                Instant.class, secondId)).isEqualTo(base.plusSeconds(30));
        assertThat(activityCount("AI_ALERT_RAISED")).isEqualTo(2); // one per window
    }

    @Test
    void outOfOrderObservedAtNeverMovesLastDetectedAtBackwards() {
        // P2-4 regression: an accepted out-of-order detection (still within
        // the attempt window) must not push last_detected_at below
        // first_detected_at (chk_ai_alerts_period → 500 rollback).
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base, 1, 0.9)));
        clock.advance(Duration.ofSeconds(3));
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.plusSeconds(2), 2, 0.6)));
        clock.advance(Duration.ofSeconds(1));
        // Out-of-order: observedAt BEFORE the window's first_detected_at
        // (still ≥ startedAt-5s so it is ACCEPTED, not quarantined).
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.minusSeconds(2), 3, 0.4)));

        assertThat(alertCount()).isEqualTo(1);
        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT detection_count, first_detected_at, last_detected_at "
                        + "FROM monitoring_ai_alerts WHERE attempt_id=?", attemptId);
        assertThat(((Number) alert.get("detection_count")).intValue()).isEqualTo(3);
        assertThat(((java.sql.Timestamp) alert.get("first_detected_at")).toInstant())
                .isEqualTo(base);
        assertThat(((java.sql.Timestamp) alert.get("last_detected_at")).toInstant())
                .isEqualTo(base.plusSeconds(2));
    }

    @Test
    void raceLoserRecheckSeparatesIncidentsInsteadOfMerging() {
        // P2-6 regression: simulate the open-race loser path — close the
        // winner's OPEN window behind the finder used by the gap check, then
        // aggregate a detection BEYOND the cooldown: it must close+reopen a
        // NEW window, not extend the winner's.
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base, 1, 0.9)));
        clock.advance(Duration.ofSeconds(30));
        // Sneak a second window in as the "winner" of a concurrent race by
        // driving the same gap flow: detection at +30s closes window 1 and
        // opens window 2 — then simulate the loser by aggregating an OLD
        // detection (base+1s, within window 1's cooldown but window 1 is
        // CLOSED): it lands in window 2 via GREATEST-extend and never
        // corrupts period ordering.
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.plusSeconds(30), 2, 0.6)));
        clock.advance(Duration.ofSeconds(1));
        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base.plusSeconds(1), 3, 0.55)));

        List<Map<String, Object>> windows = jdbc.queryForList(
                "SELECT id, status, detection_count, first_detected_at, last_detected_at "
                        + "FROM monitoring_ai_alerts WHERE attempt_id=? ORDER BY id", attemptId);
        assertThat(windows).hasSize(2);
        for (Map<String, Object> w : windows) {
            // chk_ai_alerts_period holds on every window (no 500 rollback).
            assertThat(((java.sql.Timestamp) w.get("last_detected_at")).toInstant()
                    .isBefore(((java.sql.Timestamp) w.get("first_detected_at")).toInstant()))
                    .as("window %s period invariant", w.get("id"))
                    .isFalse();
        }
    }

    @Test
    void studentReplayWithFreshEventIdRepeatedSequenceIsDuplicate() {
        // P2-1: the V39 (attempt_id, provenance, sequence) partial unique —
        // the student path maps the violation to DUPLICATE as well.
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base, 7, 0.9)));
        clock.advance(Duration.ofSeconds(2));
        // Same sequence 7, fresh eventId (phoneDetection randomizes ids).
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base.plusSeconds(1), 7, 0.9)));

        assertThat(detectionCount()).isEqualTo(1);
        assertThat(alertCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT detection_count FROM monitoring_ai_alerts", Integer.class)).isEqualTo(1);
    }

    @Test
    void detectionCountSaturatesAtSixtyFour() {
        // P2-1: 70 in-cooldown events — the stored count saturates at 64
        // (matches the AI_ALERT_UPDATED notify ceiling). Paced as full
        // batches (8 events each) every 12s to stay under the 6 batches/min
        // student rate limit; events inside a batch are 1.5s apart so every
        // event lands inside the 20s cooldown → ONE window.
        int sequence = 0;
        long offset = 0;
        for (int b = 0; b < 9; b++) {
            clock.advance(Duration.ofSeconds(12));
            offset += 12;
            List<AiDetectionBatchRequest.Item> items = new ArrayList<>();
            for (int i = 0; i < 8 && sequence < 70; i++, sequence++) {
                items.add(phoneDetection(base.plusSeconds(offset - 12 + i * 2L),
                        sequence, 0.5 + (sequence % 10) * 0.04));
            }
            ingestService.ingestStudent(studentId, attemptId,
                    new AiDetectionBatchRequest(items));
        }
        assertThat(detectionCount()).isEqualTo(70);
        assertThat(alertCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT detection_count FROM monitoring_ai_alerts", Integer.class)).isEqualTo(64);
    }

    @Test
    void windowOlderThanMaxSpanClosesAndOpensNewWindow() {
        // P2-1: continuous in-cooldown detections (15s apart) crossing the
        // 10-minute max span split into two incident windows.
        for (int i = 0; i <= 42; i++) { // ~10.5 min of continuous activity
            if (i > 0) {
                clock.advance(Duration.ofSeconds(15));
            }
            ingestService.ingestStudent(studentId, attemptId,
                    batch(phoneDetection(base.plusSeconds(15L * i), 100 + i, 0.8)));
        }
        List<Map<String, Object>> windows = jdbc.queryForList(
                "SELECT status FROM monitoring_ai_alerts WHERE attempt_id=? ORDER BY id",
                attemptId);
        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).get("status")).isEqualTo("CLOSED");
        assertThat(windows.get(1).get("status")).isEqualTo("OPEN");
        assertThat(activityCount("AI_ALERT_RAISED")).isEqualTo(2);
    }

    @Test
    void personNotVisibleUsesLongerFortyFiveSecondCooldown() {
        ingestService.ingestStudent(studentId, attemptId,
                batch(item("PERSON_NOT_OBSERVED", base, 1)));
        clock.advance(Duration.ofSeconds(30)); // 30s < 45s → same window
        ingestService.ingestStudent(studentId, attemptId,
                batch(item("PERSON_NOT_OBSERVED", base.plusSeconds(30), 2)));

        assertThat(alertCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT detection_count FROM monitoring_ai_alerts", Integer.class)).isEqualTo(2);
        assertThat(alertTypeOf()).isEqualTo("PERSON_NOT_VISIBLE");
    }

    @Test
    void quarantinedDetectionNeverAggregates() {
        finishAttempt(jdbc, attemptId, base);
        ingestService.ingestStudent(studentId, attemptId, batch(phoneDetection(base, 1, 0.9)));
        assertThat(alertCount()).isZero();

        // A later legit ingest for a NEW in-progress attempt of the same
        // session still aggregates normally (quarantine is per attempt).
        long attempt2 = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attempt2, base, null);
        ingestService.ingestStudent(studentId, attempt2, batch(phoneDetection(base, 1, 0.9)));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_ai_alerts WHERE attempt_id=?", Long.class, attempt2))
                .isEqualTo(1);
    }

    // === grading-domain isolation (§7: no grade-table writes) ===

    @Test
    void fullIngestFlowWritesNothingToGradeTables() {
        // The Testcontainers database is shared across suites, so the
        // assertion is on the DELTA the ingest flow produces, not on
        // absolute counts (other committed fixtures may legitimately hold
        // grade rows of their own).
        long gradesBefore = jdbc.queryForObject("SELECT count(*) FROM grades", Long.class);
        long itemsBefore = jdbc.queryForObject("SELECT count(*) FROM grade_items", Long.class);

        ingestService.ingestStudent(studentId, attemptId,
                batch(phoneDetection(base, 1, 0.9), phoneDetection(base.plusSeconds(1), 2, 0.8)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM grades", Long.class) - gradesBefore)
                .as("AI ingest never writes grades").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM grade_items", Long.class) - itemsBefore)
                .as("AI ingest never writes grade_items").isZero();
    }

    // ==================================================================
    // rate limiting — dedicated low-limit context (3 batches / window)
    // ==================================================================

    @Nested
    @TestPropertySource(properties = "quizopia.monitoring.ai-ingest.rate-limit=3")
    class RateLimitWindowTests {

        @Test
        void fourthIngestInWindowDenied429WithRetryAfter() {
            for (int i = 0; i < 3; i++) {
                ingestService.ingestStudent(studentId, attemptId,
                        batch(phoneDetection(base, i, 0.5)));
            }
            assertThat(detectionCount()).isEqualTo(3);

            assertThatThrownBy(() -> ingestService.ingestStudent(studentId, attemptId,
                    batch(phoneDetection(base, 9, 0.5))))
                    .isInstanceOfSatisfying(MonitoringException.class, ex -> {
                        assertThat(ex.getErrorCode().code())
                                .isEqualTo("MONITORING_RATE_LIMITED");
                        assertThat(ex.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
                    });
            assertThat(detectionCount()).as("the denied batch stored nothing").isEqualTo(3);
        }
    }

    // === helpers ===

    private AiDetectionBatchRequest.Item phoneDetection(Instant observedAt, long seq,
            double confidence) {
        return new AiDetectionBatchRequest.Item(UUID.randomUUID(), "PHONE_OBSERVED", observedAt,
                seq, "yolo26s", "8.4.64-onnx", "ORT_WEB_WEBGPU", 640, 480, 55,
                List.of(new AiDetectionBatchRequest.DetectedObject(67, "cell phone",
                        confidence, List.of(0.1, 0.2, 0.3, 0.4))));
    }

    private AiDetectionBatchRequest.Item item(String type, Instant observedAt, long seq) {
        return new AiDetectionBatchRequest.Item(UUID.randomUUID(), type, observedAt, seq,
                "yolo26s", "8.4.64-onnx", "ORT_WEB_WASM", 640, 480, 40, List.of());
    }

    private AiDetectionBatchRequest batch(AiDetectionBatchRequest.Item... items) {
        return new AiDetectionBatchRequest(List.of(items));
    }

    private UUID eventIdOf(AiDetectionBatchResponse response, int index) {
        return response.results().get(index).eventId();
    }

    private long detectionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_ai_detections",
                Long.class);
    }

    private long alertCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_ai_alerts", Long.class);
    }

    private String alertTypeOf() {
        return jdbc.queryForObject("SELECT alert_type FROM monitoring_ai_alerts", String.class);
    }

    private long activityCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM attempt_activity_events WHERE event_type=?",
                Long.class, eventType);
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles "
                        + "WHERE code='STUDENT') AND permission_id="
                        + "(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private static String code(Throwable e) {
        if (e instanceof MonitoringException me) {
            return me.getErrorCode().code();
        }
        if (e instanceof AttemptException ae) {
            return ae.getErrorCode().code();
        }
        throw new AssertionError("unexpected exception type: " + e.getClass(), e);
    }
}
