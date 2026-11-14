package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.monitoring.application.AttemptActivityIngestService;
import com.quizopia.backend.monitoring.application.AttemptTimelineService;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchRequest;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code POST /api/attempts/{attemptId}/activity-events}
 * (P4-A2, issue #81): the auth matrix (ownership + ATTEMPT_ACTIVITY_INGEST),
 * eventId idempotency (in-batch duplicates and retried batches), verbatim
 * retention of client timestamps/sequences (clock skew and out-of-order
 * delivery are observations, never corrected), the ingest bounds (batch size,
 * metadata size/shape, client-only event types), and the real lifecycle flow
 * — ATTEMPT_STARTED (source SERVER) written by {@code AttemptService} shares
 * the timeline with ingested CLIENT rows in (server_received_at, id) order.
 *
 * <p>Rate-limit saturation runs in a {@link Nested} class with a dedicated
 * context ({@code rate-limit=3} via {@code @TestPropertySource}) so the outer
 * tests keep the production-shaped default window.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptActivityIngestIntegrationTests {

    @Autowired private AttemptActivityIngestService ingestService;
    @Autowired private AttemptTimelineService timelineService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;

    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Instant base;
    private long studentUserId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(base);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('ia','ia@t.com','h','IA')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + studentUserId + "," + subj + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + studentUserId + "," + subj + ",'E','Exam','PUBLISHED',now(),4)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eq = eq(exam, sec, bank, "SINGLE_CHOICE", 0);
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'S','Sess','OPEN','" + base.minusSeconds(3600) + "','" + base.plusSeconds(7200) + "',2," + studentUserId + ",'" + base.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + session + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
    }

    // === authorization ===

    @Test void foreignAttemptRejected403() {
        long other = newStudent("ifo");
        assertThatThrownBy(() -> ingestService.ingest(other, attemptId, batch(item("TAB_HIDDEN", 1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test void missingAttemptRejected404() {
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, 999999L, batch(item("TAB_HIDDEN", 1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ATTEMPT_NOT_FOUND"));
    }

    @Test void revokedIngestPermissionRejected403() {
        revoke("ATTEMPT_ACTIVITY_INGEST");
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(item("TAB_HIDDEN", 1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }



    @Test void duplicateEventIdWithinBatchFirstAcceptedSecondDuplicate() {
        UUID shared = UUID.randomUUID();
        ActivityEventBatchRequest batch = batch(
                new ActivityEventBatchRequest.Item(shared, "TAB_HIDDEN", base.minusSeconds(5), 1L, INSTANCE_ID, null),
                new ActivityEventBatchRequest.Item(shared, "TAB_HIDDEN", base.minusSeconds(5), 1L, INSTANCE_ID, null));
        ActivityEventBatchResponse r = ingestService.ingest(studentUserId, attemptId, batch);
        assertThat(r.acceptedCount()).isEqualTo(1);
        assertThat(r.duplicateCount()).isEqualTo(1);
        assertThat(r.results().get(0).eventId()).isEqualTo(shared);
        assertThat(r.results().get(0).status()).isEqualTo(ActivityEventBatchResponse.Status.ACCEPTED);
        assertThat(r.results().get(1).status()).isEqualTo(ActivityEventBatchResponse.Status.DUPLICATE);
        assertThat(eventCount()).isEqualTo(2); // the ATTEMPT_STARTED lifecycle row + 1 client row
    }

    @Test void retriedIdenticalBatchAllDuplicateZeroNewRows() {
        List<ActivityEventBatchRequest.Item> items = List.of(
                item("TAB_HIDDEN", 1), item("WINDOW_FOCUS_LOST", 2), item("PAGE_RELOADED", 3));
        ActivityEventBatchResponse first = ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(items));
        assertThat(first.acceptedCount()).isEqualTo(3);
        assertThat(first.duplicateCount()).isZero();
        long rowsAfterFirst = clientEventCount();

        ActivityEventBatchResponse retry = ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(items));
        assertThat(retry.acceptedCount()).isZero();
        assertThat(retry.duplicateCount()).isEqualTo(3);
        assertThat(retry.results())
                .allSatisfy(res -> assertThat(res.status()).isEqualTo(ActivityEventBatchResponse.Status.DUPLICATE));
        assertThat(clientEventCount()).isEqualTo(rowsAfterFirst); // zero new rows
    }

    // === client timestamp / sequence retention ===

    @Test void outOfOrderClientSequenceAcceptedAndRetained() {
        ActivityEventBatchResponse r = ingestService.ingest(studentUserId, attemptId, batch(
                item("QUESTION_NAVIGATED", 7), item("TAB_HIDDEN", 2), item("PAGE_RELOADED", 9)));
        assertThat(r.acceptedCount()).isEqualTo(3);
        Set<Long> stored = jdbc.queryForList(
                "SELECT client_sequence FROM attempt_activity_events WHERE attempt_id=? AND source='CLIENT'",
                Long.class, attemptId).stream().collect(Collectors.toSet());
        assertThat(stored).as("out-of-order sequences retained verbatim").containsExactlyInAnyOrder(2L, 7L, 9L);
    }

    @Test void clockSkewPastAndFutureAcceptedVerbatim() {
        Instant past = base.minus(Duration.ofHours(2));
        Instant future = base.plus(Duration.ofMinutes(5));
        ActivityEventBatchResponse r = ingestService.ingest(studentUserId, attemptId, batch(
                new ActivityEventBatchRequest.Item(UUID.randomUUID(), "CONNECTION_LOST", past, 1L, INSTANCE_ID, null),
                new ActivityEventBatchRequest.Item(UUID.randomUUID(), "CONNECTION_RESTORED", future, 2L, INSTANCE_ID, null)));
        assertThat(r.acceptedCount()).isEqualTo(2);
        List<Instant> stored = jdbc.queryForList(
                "SELECT client_occurred_at FROM attempt_activity_events WHERE attempt_id=? AND source='CLIENT' ORDER BY client_sequence",
                Instant.class, attemptId);
        assertThat(stored).as("client timestamps stored verbatim (skew retained, never corrected)")
                .containsExactly(past, future);
    }

    @Test void year2500Rejected400() {
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId,
                batch(new ActivityEventBatchRequest.Item(UUID.randomUUID(), "TAB_HIDDEN",
                        Instant.parse("2500-01-01T00:00:00Z"), 1L, INSTANCE_ID, null))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_VALIDATION_ERROR"));
        assertThat(clientEventCount()).isZero();
    }

    // === event-type provenance ===

    @Test void serverTypeFromClientRejected400() {
        for (String serverType : new String[]{"ANSWER_ACCEPTED", "ATTEMPT_STARTED"}) {
            assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(item(serverType, 1))))
                    .as("server-confirmed type %s must never be accepted from a client", serverType)
                    .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_EVENT_TYPE_REJECTED"));
        }
    }

    @Test void unknownTypeRejected400() {
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(item("CAMERA_CONNECTED", 1))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_EVENT_TYPE_REJECTED"));
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(item("NOT_A_REAL_TYPE", 2))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_EVENT_TYPE_REJECTED"));
    }

    // === bounds ===

    @Test void batchOf26Rejected400BeforeAnyInsert() {
        List<ActivityEventBatchRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            items.add(item("TAB_HIDDEN", i));
        }
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(items)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_BATCH_TOO_LARGE"));
        assertThat(clientEventCount()).isZero();
    }

    @Test void metadataOver2KbRejected400() {
        ActivityEventBatchRequest.Item oversized = new ActivityEventBatchRequest.Item(
                UUID.randomUUID(), "TAB_HIDDEN", base, 1L, INSTANCE_ID,
                objectMapper.readTree("{\"blob\":\"" + "x".repeat(3000) + "\"}"));
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(oversized)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_METADATA_TOO_LARGE"));
        assertThat(clientEventCount()).isZero();
    }

    @Test void metadataNonObjectRejected400() {
        ActivityEventBatchRequest.Item array = new ActivityEventBatchRequest.Item(
                UUID.randomUUID(), "TAB_HIDDEN", base, 1L, INSTANCE_ID, objectMapper.readTree("[1,2]"));
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(array)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_VALIDATION_ERROR"));
        ActivityEventBatchRequest.Item scalar = new ActivityEventBatchRequest.Item(
                UUID.randomUUID(), "TAB_HIDDEN", base, 2L, INSTANCE_ID, objectMapper.readTree("\"x\""));
        assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, batch(scalar)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_VALIDATION_ERROR"));
    }

    @Test void batchOf25ValidAccepted() {
        String[] types = {"TAB_HIDDEN", "TAB_VISIBLE", "WINDOW_FOCUS_LOST", "WINDOW_FOCUS_GAINED", "PAGE_RELOADED"};
        List<ActivityEventBatchRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            items.add(item(types[i % types.length], i));
        }
        ActivityEventBatchResponse r = ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(items));
        assertThat(r.acceptedCount()).isEqualTo(25);
        assertThat(r.duplicateCount()).isZero();
        assertThat(clientEventCount()).isEqualTo(25);
    }

    // === real lifecycle flow ===

    @Test void lifecycleStartPlusClientEventShareOrderedTimeline() {
        ingestService.ingest(studentUserId, attemptId, batch(item("PAGE_RELOADED", 1)));

        String source = jdbc.queryForObject(
                "SELECT source FROM attempt_activity_events WHERE attempt_id=? AND event_type='ATTEMPT_STARTED'",
                String.class, attemptId);
        assertThat(source).as("ATTEMPT_STARTED is recorded by AttemptService as a SERVER event").isEqualTo("SERVER");
        assertThat(eventCount()).isEqualTo(2);

        AttemptTimelineResponse timeline = timelineService.timeline(
                studentUserId, attemptId, null, 50, null, null, null, null);
        assertThat(timeline.items()).hasSize(2);
        assertThat(timeline.items().get(0).eventType()).isEqualTo("ATTEMPT_STARTED");
        assertThat(timeline.items().get(0).source()).isEqualTo("SERVER");
        assertThat(timeline.items().get(1).eventType()).isEqualTo("PAGE_RELOADED");
        assertThat(timeline.items().get(1).source()).isEqualTo("CLIENT");
        assertThat(timeline.items())
                .as("(serverReceivedAt, id) ascending")
                .isSortedAccordingTo(java.util.Comparator.comparing(AttemptTimelineResponse.TimelineItem::serverReceivedAt));
    }

    // ==================================================================
    // rate limiting — dedicated low-limit context (3 batches / window)
    // ==================================================================

    @Nested
    @TestPropertySource(properties = "quizopia.monitoring.activity-ingest.rate-limit=3")
    class RateLimitWindowTests {

        @Test void fourthIngestInWindowDenied429WithRetryAfter() {
            ActivityEventBatchRequest first = batch(item("TAB_HIDDEN", 1));
            ActivityEventBatchRequest second = batch(item("TAB_VISIBLE", 2));
            ActivityEventBatchRequest third = batch(item("WINDOW_FOCUS_LOST", 3));
            assertThat(ingestService.ingest(studentUserId, attemptId, first).acceptedCount()).isEqualTo(1);
            assertThat(ingestService.ingest(studentUserId, attemptId, second).acceptedCount()).isEqualTo(1);
            assertThat(ingestService.ingest(studentUserId, attemptId, third).acceptedCount()).isEqualTo(1);
            assertThat(clientEventCount()).isEqualTo(3);

            ActivityEventBatchRequest fourth = batch(item("PAGE_RELOADED", 4));
            assertThatThrownBy(() -> ingestService.ingest(studentUserId, attemptId, fourth))
                    .isInstanceOfSatisfying(MonitoringException.class, ex -> {
                        assertThat(ex.getErrorCode().code()).isEqualTo("MONITORING_RATE_LIMITED");
                        assertThat(ex.getRetryAfterSeconds())
                                .as("the global handler renders this as the Retry-After header (>=1s)")
                                .isGreaterThanOrEqualTo(1);
                    });
            assertThat(clientEventCount()).as("the denied batch stored nothing").isEqualTo(3);
        }
    }

    // === helpers ===

    private ActivityEventBatchRequest.Item item(String type, long seq) {
        return new ActivityEventBatchRequest.Item(UUID.randomUUID(), type, base.minusSeconds(seq + 1), seq, INSTANCE_ID, null);
    }

    private ActivityEventBatchRequest batch(ActivityEventBatchRequest.Item... items) {
        return new ActivityEventBatchRequest(List.of(items));
    }

    private long newStudent(String username) {
        long id = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + username + "','" + username + "@t.com','h','" + username.toUpperCase() + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + id + "," + roleId + ")");
        return id;
    }

    private long eventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM attempt_activity_events WHERE attempt_id=?", Long.class, attemptId);
    }

    private long clientEventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM attempt_activity_events WHERE attempt_id=? AND source='CLIENT'", Long.class, attemptId);
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
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

    private long eq(long exam, long sec, long bank, String type, int position) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + studentUserId + ")");
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
