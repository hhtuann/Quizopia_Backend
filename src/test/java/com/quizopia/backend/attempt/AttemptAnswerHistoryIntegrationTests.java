package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.SaveAnswerResponse;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-A3 (issue #82) — atomic accepted-answer history. Every server-ACCEPTED
 * autosave writes EXACTLY ONE history row and ONE server-confirmed
 * ANSWER_ACCEPTED activity event, in the SAME transaction as the conditional
 * UPSERT. Stale / retried / duplicate / deadline-failed saves write ZERO
 * rows. A history-write failure rolls the whole autosave back. Grading and
 * the autosave response contract are unchanged.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptAnswerHistoryIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private com.quizopia.backend.attempt.application.AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;
    @Autowired private ObjectMapper objectMapper;

    private long studentUserId;
    private long attemptId;
    private long aqSingleId;
    private long aqNumericId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('hist','hist@t.com','h','H')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + java.util.UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + studentUserId + "," + subj + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + studentUserId + "," + subj + ",'E','Exam','PUBLISHED',now(),4)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eqSingle = eq(exam, sec, bank, "SINGLE_CHOICE", 0);
        opts(eqSingle);
        String numericKey = "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long eqNumeric = eqNumeric(exam, sec, bank, 1, numericKey);
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'HS','Sess','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + session + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = aqId(eqSingle);
        aqNumericId = aqId(eqNumeric);
    }

    // === atomicity: accepted ⇒ exactly one history row + one event ===

    @Test
    void firstAcceptedWritesExactlyOneHistoryRowAndEvent() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        assertThat(r.accepted()).isTrue();

        assertThat(historyCount()).isEqualTo(1);
        Map<String, Object> row = historyRow();
        assertThat(row.get("attempt_question_id")).isEqualTo(aqSingleId);
        assertThat(row.get("accepted_sequence")).isEqualTo(1L);
        assertThat(row.get("accepted_at")).isNotNull();
        assertThat(((java.sql.Timestamp) row.get("accepted_at")).toInstant())
                .isEqualTo(r.savedAt()); // the UPSERT-decided saved_at

        assertThat(eventCount("ANSWER_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void acceptedHistoryRowCarriesCanonicalPayload() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("C"), 1));
        String payload = jdbc.queryForObject(
                "SELECT answer_payload::text FROM attempt_answer_history WHERE attempt_id=? AND attempt_question_id=?",
                String.class, attemptId, aqSingleId);
        assertThat(payload).contains("\"selectedOptionKey\"");
        assertThat(payload).contains("\"C\"");
    }

    @Test
    void replacementAcceptedAppendsSecondVersion() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 2));
        assertThat(historyCount()).isEqualTo(2);
        List<Long> sequences = jdbc.queryForList(
                "SELECT accepted_sequence FROM attempt_answer_history WHERE attempt_id=? AND attempt_question_id=? ORDER BY accepted_sequence",
                Long.class, attemptId, aqSingleId);
        assertThat(sequences).containsExactly(1L, 2L);
        assertThat(eventCount("ANSWER_ACCEPTED")).isEqualTo(2);
    }

    @Test
    void acceptedClearStoresNullPayloadHistoryRow() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, null, 2));
        Integer nullPayloadRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_answer_history WHERE attempt_id=? AND attempt_question_id=? AND answer_payload IS NULL",
                Integer.class, attemptId, aqSingleId);
        assertThat(nullPayloadRows).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(2);
    }

    // === atomicity: NOT accepted ⇒ zero writes ===

    @Test
    void staleEqualSequenceWritesNoHistoryOrEvent() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 5));
        SaveAnswerResponse stale = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 5));
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.reason()).isEqualTo("STALE_SEQUENCE");
        assertThat(historyCount()).isEqualTo(1); // only the first accept
        assertThat(eventCount("ANSWER_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void staleLowerSequenceWritesNoHistoryOrEvent() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 7));
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 3)).accepted()).isFalse();
        assertThat(historyCount()).isEqualTo(1);
        assertThat(eventCount("ANSWER_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void retriedDuplicateSaveAddsZeroHistoryRows() {
        // Network-loss scenario: client retries the exact same request after
        // the first one already committed.
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 4));
        em.clear();
        SaveAnswerResponse retry = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 4));
        assertThat(retry.accepted()).isFalse(); // not newer than itself
        assertThat(historyCount()).isEqualTo(1);
        assertThat(eventCount("ANSWER_ACCEPTED")).isEqualTo(1);
    }

    @Test
    void deadlineExceededWritesNoHistoryOrEvent() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED"));
        assertThat(historyCount()).isZero();
        assertThat(eventCount("ANSWER_ACCEPTED")).isZero();
    }

    @Test
    void invalidPayloadWritesNoHistoryOrEvent() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, json("\"junk\""), 1)))
                .isInstanceOf(AttemptException.class);
        assertThat(historyCount()).isZero();
        assertThat(eventCount("ANSWER_ACCEPTED")).isZero();
    }

    @Test
    void gradingCorrectAfterHistoryWrites() {
        // Answer the single-choice question correctly (option C is the key)
        // and the numeric question correctly, then submit and grade through
        // the REAL submit path — history writes must not disturb grading.
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("C"), 1));
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqNumericId, numeric("2.50"), 1));

        submitService.submitAttempt(studentUserId, attemptId,
                new com.quizopia.backend.attempt.dto.SubmitRequest("grade-history-key-1"));

        Map<String, Object> grade = jdbc.queryForMap(
                "SELECT final_score, max_score FROM grades WHERE attempt_id=?", attemptId);
        assertThat(((Number) grade.get("final_score")).longValue())
                .isEqualTo(((Number) grade.get("max_score")).longValue()); // all correct
        assertThat(historyCount()).isEqualTo(2);
        assertThat(eventCount("ATTEMPT_SUBMITTED")).isEqualTo(1);
    }

    @Test
    void timedOutEventUsesTrueReceiptTimeNotBackdatedDeadline() {
        // Review TL-1 regression: server_received_at must be the receipt
        // instant (clock at sweep time), never the business deadline — a
        // backdated row would sort before existing cursors and be skipped
        // forever by paginating readers. submittedAt stays deadline-exact.
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(5));
        submitService.finalizeAttempt(attemptId);
        var row = jdbc.queryForMap(
                "SELECT server_received_at, metadata::text AS meta FROM attempt_activity_events "
                        + "WHERE attempt_id=? AND event_type='ATTEMPT_TIMED_OUT'", attemptId);
        Instant received = ((java.sql.Timestamp) row.get("server_received_at")).toInstant();
        assertThat(received).isAfter(deadline); // true receipt, not backdated
        assertThat((String) row.get("meta")).contains("deadlineAt");
        // submittedAt semantics unchanged: recorded at the deadline.
        Instant submittedAt = jdbc.queryForObject(
                "SELECT submitted_at FROM attempts WHERE id=?", Instant.class, attemptId);
        assertThat(submittedAt).isEqualTo(deadline);
    }

    // === helpers ===

    private SaveAnswerRequest req(long aqId, JsonNode payload, long seq) {
        return new SaveAnswerRequest(aqId, null, payload, seq, null);
    }

    private JsonNode single(String key) {
        return json("{\"selectedOptionKey\":\"" + key + "\"}");
    }

    private JsonNode numeric(String value) {
        return json("{\"value\":\"" + value + "\"}");
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int historyCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_answer_history WHERE attempt_id=?", Integer.class, attemptId);
    }

    private Map<String, Object> historyRow() {
        return jdbc.queryForMap(
                "SELECT attempt_question_id, accepted_sequence, accepted_at FROM attempt_answer_history "
                        + "WHERE attempt_id=? AND attempt_question_id=?",
                attemptId, aqSingleId);
    }

    private int eventCount(String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events WHERE attempt_id=? AND event_type=? AND source='SERVER'",
                Integer.class, attemptId, type);
    }

    private long aqId(long eqId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?",
                Long.class, attemptId, eqId);
    }

    private static String code(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private long eq(long exam, long sec, long bank, String type, int position) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + studentUserId + ")");
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long eqNumeric(long exam, long sec, long bank, int position, String numericKey) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN" + position + "'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + q + ",1,'NUMERIC_FILL','n',1,'{}'::jsonb," + numericKey + "," + studentUserId + ")");
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','NUMERIC_FILL','n',1," + position + ",'{}'::jsonb," + numericKey + ")");
    }

    private void opts(long eqId) {
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqId + ",'A','a',false,0),(" + eqId + ",'B','b',false,1),(" + eqId + ",'C','c',true,2),(" + eqId + ",'D','d',false,3)");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
