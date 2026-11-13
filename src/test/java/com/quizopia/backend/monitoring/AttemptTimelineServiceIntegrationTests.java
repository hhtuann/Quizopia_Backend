package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AttemptActivityIngestService;
import com.quizopia.backend.monitoring.application.AttemptTimelineService;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchRequest;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse.Status;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse.TimelineItem;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code GET /api/attempts/{attemptId}/timeline}
 * (P4-A4, issue #83): the IDOR-safe authorization matrix (owner learner;
 * session-owner teacher with EXAM_SESSION_MONITOR — the same gate as the
 * STOMP teacher topic; generic 403/404 for everyone else), stable
 * (server_received_at, id) keyset pagination through the opaque cursor
 * (page stability under repeated requests and late-arriving events), the
 * types/source filters, malformed-cursor rejection, and the no-answer-leak
 * contract of ANSWER_ACCEPTED metadata.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptTimelineServiceIntegrationTests {

    @Autowired private AttemptTimelineService timelineService;
    @Autowired private AttemptActivityIngestService ingestService;
    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;

    private static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private Instant base;
    private long studentUserId;
    private long teacherUserId;
    private long attemptId;
    private long aqSingleId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(base);
        studentUserId = newUser("tst", "STUDENT");
        teacherUserId = newUser("ttc", "TEACHER");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subj + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherUserId + "," + subj + ",'E','Exam','PUBLISHED',now(),2)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eqSingle = eq(exam, sec, bank, "SINGLE_CHOICE", 0);
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqSingle + ",'A','a',false,0),(" + eqSingle + ",'B','b',false,1),(" + eqSingle + ",'C','c',true,2),(" + eqSingle + ",'D','d',false,3)");
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'S','Sess','OPEN','" + base.minusSeconds(3600) + "','" + base.plusSeconds(7200) + "',2," + teacherUserId + ",'" + base.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + session + "," + teacherUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?", Long.class, attemptId, eqSingle);
    }

    // === authorization matrix ===

    @Test void ownerReadsTimeline() {
        AttemptTimelineResponse r = timeline(studentUserId, null, 50, null, null);
        assertThat(r.attemptId()).isEqualTo(attemptId);
        assertThat(r.items()).isNotEmpty();
        assertThat(r.items().get(0).eventType()).isEqualTo("ATTEMPT_STARTED");
    }

    @Test void otherStudentRejected403() {
        long other = newUser("tos", "STUDENT");
        assertThatThrownBy(() -> timeline(other, null, 50, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(mcode(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test void sessionOwnerTeacherReads() {
        AttemptTimelineResponse r = timeline(teacherUserId, null, 50, null, null);
        assertThat(r.items()).isNotEmpty();
        assertThat(r.items().get(0).source()).isEqualTo("SERVER");
    }

    @Test void otherTeacherRejected403() {
        long other = newUser("tot", "TEACHER");
        assertThatThrownBy(() -> timeline(other, null, 50, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(mcode(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test void missingAttemptRejected404() {
        assertThatThrownBy(() -> timelineService.timeline(studentUserId, 999999L, null, 50, null, null, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(mcode(e)).isEqualTo("MONITORING_ATTEMPT_NOT_FOUND"));
    }

    // === cursor pagination ===

    @Test void limitTwoPagesThreeTimesAscendingAndStable() {
        // 4 client rows + the ATTEMPT_STARTED lifecycle row = 5 events.
        ingest("TAB_HIDDEN", "WINDOW_FOCUS_LOST", "QUESTION_NAVIGATED", "PAGE_RELOADED");

        AttemptTimelineResponse full = timeline(studentUserId, null, 100, null, null);
        assertThat(full.items()).hasSize(5);

        List<TimelineItem> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            AttemptTimelineResponse page = timeline(studentUserId, cursor, 2, null, null);
            walked.addAll(page.items());
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            // Repeating the SAME middle-page request is deterministic.
            AttemptTimelineResponse repeat = timeline(studentUserId, cursor, 2, null, null);
            assertThat(eventIds(repeat.items())).isEqualTo(eventIds(page.items()));
            cursor = page.nextCursor();
        }
        assertThat(pages).as("5 events at limit=2 walk exactly 3 pages").isEqualTo(3);
        assertThat(walked).as("concatenated pages equal the full list, in order")
                .extracting(TimelineItem::eventId)
                .containsExactlyElementsOf(eventIds(full.items()));
        assertThat(walked).as("(serverReceivedAt, id) strictly ascending across pages")
                .isSortedAccordingTo(java.util.Comparator.comparing(TimelineItem::serverReceivedAt));
    }

    @Test void lateArrivingEventAppearsOnlyInLaterPageAndPage1Unchanged() {
        ingest("TAB_HIDDEN", "WINDOW_FOCUS_LOST", "QUESTION_NAVIGATED", "PAGE_RELOADED");

        AttemptTimelineResponse page1 = timeline(studentUserId, null, 2, null, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        // A late flush (client_occurred_at in the past, received later) lands
        // after every already-served row — pages already served never change.
        UUID lateId = UUID.randomUUID();
        ingestItems(new ActivityEventBatchRequest.Item(lateId, "CONNECTION_LOST",
                base.minusSeconds(600), 99L, INSTANCE_ID, null));

        AttemptTimelineResponse page1Again = timeline(studentUserId, null, 2, null, null);
        assertThat(eventIds(page1Again.items())).as("page 1 repeated unchanged")
                .containsExactlyElementsOf(eventIds(page1.items()));

        List<UUID> rest = new ArrayList<>();
        String cursor = page1.nextCursor();
        while (cursor != null) {
            AttemptTimelineResponse page = timeline(studentUserId, cursor, 2, null, null);
            rest.addAll(eventIds(page.items()));
            cursor = page.nextCursor();
        }
        assertThat(rest).as("the late event appears only in a later page").contains(lateId);
        assertThat(eventIds(page1Again.items())).doesNotContain(lateId);
    }

    // === filters ===

    @Test void typesFilterReturnsOnlyRequestedTypes() {
        ingest("TAB_HIDDEN", "WINDOW_FOCUS_LOST", "TAB_HIDDEN", "PAGE_RELOADED");
        AttemptTimelineResponse r = timeline(studentUserId, null, 50, "TAB_HIDDEN", null);
        assertThat(r.items()).hasSize(2);
        assertThat(r.items()).allSatisfy(i -> assertThat(i.eventType()).isEqualTo("TAB_HIDDEN"));

        AttemptTimelineResponse multi = timeline(studentUserId, null, 50, "TAB_HIDDEN,PAGE_RELOADED", null);
        assertThat(eventIds(multi.items())).hasSize(3);
    }

    @Test void sourceFilterServerReturnsLifecycleAndAnswerAcceptedOnly() {
        autosave(single("B"), 1);
        ingest("TAB_HIDDEN", "PAGE_RELOADED");
        AttemptTimelineResponse r = timeline(studentUserId, null, 50, null, "SERVER");
        assertThat(r.items()).extracting(TimelineItem::eventType)
                .containsExactly("ATTEMPT_STARTED", "ANSWER_ACCEPTED");
        assertThat(r.items()).allSatisfy(i -> assertThat(i.source()).isEqualTo("SERVER"));
    }

    // === cursor validation ===

    @Test void invalidCursorRejected400() {
        assertThatThrownBy(() -> timeline(studentUserId, "!!!not-base64!!!", 50, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(mcode(e)).isEqualTo("MONITORING_CURSOR_INVALID"));
        // Valid base64 but not "<epochMicros>:<id>" — still rejected.
        assertThatThrownBy(() -> timeline(studentUserId,
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("no-separator-here".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                50, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(mcode(e)).isEqualTo("MONITORING_CURSOR_INVALID"));
    }

    // === no answer leak ===

    @Test void answerAcceptedMetadataCarriesIdsOnlyAndNoAnswerPayload() {
        autosave(single("B"), 1);

        AttemptTimelineResponse r = timeline(studentUserId, null, 50, "ANSWER_ACCEPTED", null);
        assertThat(r.items()).hasSize(1);
        TimelineItem item = r.items().get(0);
        assertThat(item.eventType()).isEqualTo("ANSWER_ACCEPTED");
        assertThat(item.source()).isEqualTo("SERVER");
        JsonNode metadata = item.metadata();
        assertThat(metadata.get("attemptQuestionId").asLong()).isEqualTo(aqSingleId);
        assertThat(metadata.get("acceptedSequence").asLong()).isEqualTo(1L);

        String serialized = objectMapper.writeValueAsString(r);
        assertThat(serialized)
                .as("no answer payload field or value anywhere in the timeline response")
                .doesNotContain("selectedOptionKey")
                .doesNotContain("\"B\"");
    }

    // === helpers ===

    private AttemptTimelineResponse timeline(long userId, String cursor, int limit, String types, String source) {
        return timelineService.timeline(userId, attemptId, cursor, limit, types, source, null, null);
    }

    private void ingest(String... types) {
        List<ActivityEventBatchRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            items.add(new ActivityEventBatchRequest.Item(UUID.randomUUID(), types[i],
                    base.minusSeconds(30 + i), (long) i, INSTANCE_ID, null));
        }
        assertAllAccepted(ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(items)));
    }

    private void ingestItems(ActivityEventBatchRequest.Item... items) {
        assertAllAccepted(ingestService.ingest(studentUserId, attemptId, new ActivityEventBatchRequest(List.of(items))));
    }

    private void assertAllAccepted(com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse r) {
        assertThat(r.results()).allSatisfy(res -> assertThat(res.status()).isEqualTo(Status.ACCEPTED));
    }

    private void autosave(JsonNode payload, long seq) {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId,
                new SaveAnswerRequest(aqSingleId, null, payload, seq, null)).accepted()).isTrue();
    }

    private JsonNode single(String key) {
        return objectMapper.readTree("{\"selectedOptionKey\":\"" + key + "\"}");
    }

    private static List<UUID> eventIds(List<TimelineItem> items) {
        return items.stream().map(TimelineItem::eventId).toList();
    }

    private long newUser(String username, String role) {
        long id = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + username + "','" + username + "@t.com','h','" + username.toUpperCase() + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + role + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + id + "," + roleId + ")");
        return id;
    }

    private long eq(long exam, long sec, long bank, String type, int position) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + teacherUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + teacherUserId + ")");
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private static String mcode(Throwable e) {
        return ((MonitoringException) e).getErrorCode().code();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
