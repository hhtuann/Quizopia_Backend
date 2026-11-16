package com.quizopia.backend.monitoring;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newTeacherUser;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewHistoryResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewResponse;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4-E alert review integration tests (issue #96, ADR-010 §2.2): append-only
 * ledger semantics, optimistic concurrency, idempotency, authorization and
 * the unified-timeline review event.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AlertReviewIntegrationTests {

    @Autowired private AlertReviewService reviewService;
    @Autowired private AiDetectionIngestService ingestService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private Instant base;
    private long teacherId;
    private long otherTeacherId;
    private long studentId;
    private long sessionId;
    private long attemptId;
    private long ingestSequence;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-17T09:00:00Z");
        clock.setInstant(base);
        teacherId = newTeacherUser(jdbc, "revteacher");
        otherTeacherId = newTeacherUser(jdbc, "revother");
        studentId = newUser(jdbc, "revstudent");
        sessionId = insertOpenSession(jdbc, teacherId, base);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (?,?,?)", studentId, sessionId, teacherId);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
    }

    private long raiseAlert() {
        ingest(base.plusSeconds(ingestSequence * 10), "PHONE_OBSERVED");
        return jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, attemptId);
    }

    private void ingest(Instant observedAt, String eventType) {
        // Ingest window convention: observedAt stays within the late-accept
        // window of the (static) test clock, like the P4-C suites.
        var item = new AiDetectionBatchRequest.Item(
                UUID.randomUUID(), eventType, observedAt, ++ingestSequence, "yolo26s",
                "8.4.64-onnx", "ORT_WEB_WASM", 640, 480, 40, List.of());
        var r = ingestService.ingestStudent(studentId, attemptId,
                new AiDetectionBatchRequest(List.of(item)));
        assertThat(r.acceptedCount()).isEqualTo(1);
    }

    // === happy path + timeline event ===

    @Test
    void reviewUpdatesAlertAndEmitsTimelineEventWithoutTouchingDetectorFields() {
        long alertId = raiseAlert();
        String detectorModel = jdbc.queryForObject(
                "SELECT model_id FROM monitoring_ai_alerts WHERE id = ?", String.class, alertId);

        AlertReviewResponse response = reviewService.review(teacherId, alertId, 0,
                "FALSE_POSITIVE", "Lighting artefact", UUID.randomUUID());

        assertThat(response.idempotentReplay()).isFalse();
        assertThat(response.reviewVersion()).isEqualTo(1);
        assertThat(response.reviewStatus()).isEqualTo("FALSE_POSITIVE");
        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT review_status, review_version, reviewed_by, model_id "
                        + "FROM monitoring_ai_alerts WHERE id = ?", alertId);
        assertThat(alert.get("review_status")).isEqualTo("FALSE_POSITIVE");
        assertThat(((Number) alert.get("review_version")).intValue()).isEqualTo(1);
        assertThat(((Number) alert.get("reviewed_by")).longValue()).isEqualTo(teacherId);
        // Detector provenance untouched — a review never forges verification.
        assertThat(alert.get("model_id")).isEqualTo(detectorModel);

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT source, metadata FROM attempt_activity_events "
                        + "WHERE attempt_id = ? AND event_type = 'ALERT_REVIEW_RECORDED'",
                attemptId);
        assertThat(event.get("source")).isEqualTo("SERVER");
        var node = objectMapper.readTree(event.get("metadata").toString());
        assertThat(node.path("alertId").asLong()).isEqualTo(alertId);
        assertThat(node.path("action").asString()).isEqualTo("FALSE_POSITIVE");
        assertThat(node.path("reviewVersion").asInt()).isEqualTo(1);
        // Notes never enter the timeline.
        assertThat(event.get("metadata").toString()).doesNotContain("Lighting");
    }

    // === idempotency ===

    @Test
    void idempotentReplayReturnsStoredOutcomeEvenAfterLaterReviews() {
        long alertId = raiseAlert();
        UUID key = UUID.randomUUID();
        AlertReviewResponse first = reviewService.review(teacherId, alertId, 0,
                "MARK_VIEWED", null, key);
        assertThat(first.reviewVersion()).isEqualTo(1);

        // Same key with a STALE expectedVersion still replays (no conflict).
        AlertReviewResponse replay = reviewService.review(teacherId, alertId, 99,
                "NOTED", "different body", key);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.reviewVersion()).isEqualTo(1);
        assertThat(replay.action()).isEqualTo("MARK_VIEWED");
        assertThat(replay.note()).isNull();

        // Later review moves the version; replaying the old key is unaffected.
        reviewService.review(teacherId, alertId, 1, "NOTED", null, UUID.randomUUID());
        AlertReviewResponse replayAfter = reviewService.review(teacherId, alertId, 0,
                "MARK_VIEWED", null, key);
        assertThat(replayAfter.idempotentReplay()).isTrue();
        assertThat(replayAfter.reviewVersion()).isEqualTo(1);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId);
        assertThat(rows).isEqualTo(2L); // MARK_VIEWED + NOTED — replay appended nothing
    }

    // === optimistic concurrency ===

    @Test
    void staleExpectedVersionConflictsWithCurrentVersionInMessage() {
        long alertId = raiseAlert();
        reviewService.review(teacherId, alertId, 0, "MARK_VIEWED", null, UUID.randomUUID());
        assertThatThrownBy(() -> reviewService.review(teacherId, alertId, 0,
                "NOTED", null, UUID.randomUUID()))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT))
                .hasMessageContaining("current version 1");
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId);
        assertThat(rows).isEqualTo(1L);
    }

    // === key hygiene ===

    @Test
    void sameKeyOnDifferentAlertIsRejected() {
        long first = raiseAlert();
        clock.advance(Duration.ofSeconds(30));
        ingest(base.plusSeconds(30), "SCREEN_OBSERVED");
        long second = jdbc.queryForObject(
                "SELECT id FROM monitoring_ai_alerts WHERE alert_type = 'SCREEN_DETECTED' "
                        + "AND attempt_id = ?", Long.class, attemptId);
        UUID key = UUID.randomUUID();
        reviewService.review(teacherId, first, 0, "MARK_VIEWED", null, key);
        assertThatThrownBy(() -> reviewService.review(teacherId, second, 0,
                "MARK_VIEWED", null, key))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_REVIEW_REJECTED));
    }

    // === authorization ===

    @Test
    void studentAndForeignTeacherDenied() {
        long alertId = raiseAlert();
        assertThatThrownBy(() -> reviewService.review(studentId, alertId, 0,
                "MARK_VIEWED", null, UUID.randomUUID()))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        assertThatThrownBy(() -> reviewService.review(otherTeacherId, alertId, 0,
                "MARK_VIEWED", null, UUID.randomUUID()))
                .as("permission held, ownership missing")
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        // The failed gates never appended a ledger row.
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_alert_reviews WHERE alert_id = ?",
                Long.class, alertId);
        assertThat(rows).isZero();
    }

    @Test
    void historyReadIsGatedToo() {
        long alertId = raiseAlert();
        assertThatThrownBy(() -> reviewService.history(studentId, alertId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        assertThatThrownBy(() -> reviewService.history(otherTeacherId, alertId))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }

    // === validation ===

    @Test
    void actionAndNoteValidated() {
        long alertId = raiseAlert();
        assertThatThrownBy(() -> reviewService.review(teacherId, alertId, 0,
                "CHEATED", null, UUID.randomUUID()))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_REVIEW_REJECTED));
        assertThatThrownBy(() -> reviewService.review(teacherId, alertId, 0,
                "NOTED", "x".repeat(1001), UUID.randomUUID()))
                .satisfies(e -> assertThat(((MonitoringException) e).getErrorCode())
                        .isEqualTo(MonitoringErrorCode.MONITORING_REVIEW_REJECTED));
    }

    // === history ===

    @Test
    void historyIsAppendOnlyWithReviewerNames() {
        long alertId = raiseAlert();
        reviewService.review(teacherId, alertId, 0, "MARK_VIEWED", null, UUID.randomUUID());
        reviewService.review(teacherId, alertId, 1, "FALSE_POSITIVE", " artefact ", UUID.randomUUID());

        AlertReviewHistoryResponse history = reviewService.history(teacherId, alertId);
        assertThat(history.history()).hasSize(2);
        assertThat(history.history().get(0).version()).isEqualTo(1);
        assertThat(history.history().get(0).action()).isEqualTo("MARK_VIEWED");
        assertThat(history.history().get(1).version()).isEqualTo(2);
        // note trimmed
        assertThat(history.history().get(1).note()).isEqualTo("artefact");
        assertThat(history.history().get(1).reviewerName()).isEqualTo("REVTEACHER");
        assertThat(history.currentReview().status()).isEqualTo("FALSE_POSITIVE");
        assertThat(history.currentReview().version()).isEqualTo(2);
        assertThat(history.currentReview().reviewerName()).isEqualTo("REVTEACHER");
    }

    @Test
    void historyOfUnreviewedAlertIsUnreviewedShaped() {
        long alertId = raiseAlert();
        AlertReviewHistoryResponse history = reviewService.history(teacherId, alertId);
        assertThat(history.currentReview().status()).isEqualTo("UNREVIEWED");
        assertThat(history.currentReview().version()).isZero();
        assertThat(history.history()).isEmpty();
    }
}
