package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4-A3 (issue #82) — atomic-rollback: when the accepted-answer HISTORY
 * INSERT fails after the conditional UPSERT already accepted the answer, the
 * whole autosave transaction rolls back — the answer mutation, the
 * ANSWER_ACCEPTED activity event and {@code last_saved_at} all revert.
 *
 * <p>Uses a test-only PostgreSQL trigger ({@code BEFORE INSERT ON
 * attempt_answer_history}) that raises SQLSTATE 23000 — the same technique
 * as {@code AttemptSubmitRollbackIntegrationTests}. The class is
 * {@code NOT_SUPPORTED} so the service runs in its own transaction and the
 * rollback is observable against committed state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAnswerHistoryRollbackIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;

    private long userId;
    private long attemptId;
    private long aqId;
    private long sessionId;
    private long examId;
    private long secId;
    private long eqId;
    private long bankId;
    private long subjId;
    private long glId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        String s = "hrb";
        userId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + s + "','" + s + "@t.com','h','" + s + "')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        glId = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + java.util.UUID.randomUUID().toString().substring(0, 6) + "','G')");
        subjId = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + glId + ",'" + s + "M','M')");
        bankId = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + userId + "," + subjId + ",'B','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'Q'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + userId + ")");
        examId = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + userId + "," + subjId + ",'E','E','PUBLISHED',now(),1)");
        secId = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        eqId = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + examId + "," + secId + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eqId + ",'A','a',false,0),(" + eqId + ",'B','b',false,1),(" + eqId + ",'C','c',true,2),(" + eqId + ",'D','d',false,3)");
        sessionId = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES (" + examId + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + userId + "," + sessionId + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, sessionId, new StartAttemptRequest(null)).attemptId();
        aqId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " AND exam_question_id=" + eqId, Long.class);

        // Pre-state, committed: an accepted answer at sequence 1 (history row 1).
        autosaveService.saveAnswer(userId, attemptId, req(single("A"), 1));

        // Test-only trigger: fail EVERY subsequent attempt_answer_history INSERT.
        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_history() RETURNS TRIGGER AS $$ "
                + "BEGIN RAISE EXCEPTION USING ERRCODE = '23000', MESSAGE = 'test: history insert blocked'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_fail_history_trigger BEFORE INSERT ON attempt_answer_history "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_history()");
    }
    @AfterEach
    void tearDown() {
        // Drop trigger FIRST (real committed data in the shared container;
        // cascade deletes need history inserts unblocked is irrelevant here,
        // but the trigger/function must not leak to other tests).
        new TransactionTemplate(txm).executeWithoutResult(status -> {
            jdbc.execute("DROP TRIGGER IF EXISTS test_fail_history_trigger ON attempt_answer_history");
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_history()");
            jdbc.update("DELETE FROM attempt_activity_events WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempt_answer_history WHERE attempt_id = ?", attemptId);
            jdbc.update("DELETE FROM attempts WHERE id = ?", attemptId);
            jdbc.update("DELETE FROM exam_session_participants WHERE exam_session_id = ?", sessionId);
            jdbc.update("DELETE FROM exam_session_classes WHERE exam_session_id = ?", sessionId);
            jdbc.update("DELETE FROM session_monitoring_policies WHERE exam_session_id = ?", sessionId);
            jdbc.update("DELETE FROM exam_sessions WHERE id = ?", sessionId);
            jdbc.update("DELETE FROM exam_question_options WHERE exam_question_id = ?", eqId);
            jdbc.update("DELETE FROM exam_questions WHERE id = ?", eqId);
            jdbc.update("DELETE FROM exam_sections WHERE id = ?", secId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("DELETE FROM question_versions WHERE question_id IN (SELECT id FROM questions WHERE question_bank_id = ?)", bankId);
            jdbc.update("DELETE FROM questions WHERE question_bank_id = ?", bankId);
            jdbc.update("DELETE FROM question_banks WHERE id = ?", bankId);
            jdbc.update("DELETE FROM subjects WHERE id = ?", subjId);
            jdbc.update("DELETE FROM grade_levels WHERE id = ?", glId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
    }

    @Test
    void historyFailureRollsBackAnswerMutationEventAndLastSavedAt() {
        // Baseline committed state.
        long baselineHistory = count("attempt_answer_history");
        long baselineEvents = count("attempt_activity_events");
        Instant baselineLastSaved = lastSavedAt();

        // This save WOULD be accepted (sequence 2 > 1) but the history INSERT
        // fails → the whole autosave transaction must roll back.
        assertThatThrownBy(() -> autosaveService.saveAnswer(userId, attemptId, req(single("B"), 2)))
                .isInstanceOf(RuntimeException.class);

        // Answer row unchanged: still sequence 1, payload A.
        var answer = jdbc.queryForMap(
                "SELECT sequence_number, answer_payload->>'selectedOptionKey' AS k FROM attempt_answers WHERE attempt_id=?",
                attemptId);
        assertThat(((Number) answer.get("sequence_number")).longValue()).isEqualTo(1L);
        assertThat(answer.get("k")).isEqualTo("A");

        // No new history rows, no ANSWER_ACCEPTED event, last_saved_at unchanged.
        assertThat(count("attempt_answer_history")).isEqualTo(baselineHistory);
        assertThat(count("attempt_activity_events")).isEqualTo(baselineEvents);
        assertThat(lastSavedAt()).isEqualTo(baselineLastSaved);
    }

    @Test
    void autosaveRecoversAfterHistoryFailureResolved() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(userId, attemptId, req(single("B"), 2)))
                .isInstanceOf(RuntimeException.class);
        // Remove the blocker; the same save now commits atomically.
        jdbc.execute("DROP TRIGGER test_fail_history_trigger ON attempt_answer_history");
        var response = autosaveService.saveAnswer(userId, attemptId, req(single("B"), 2));
        assertThat(response.accepted()).isTrue();
        assertThat(count("attempt_answer_history")).isEqualTo(2);
    }

    // === helpers ===

    private SaveAnswerRequest req(JsonNode payload, long seq) {
        return new SaveAnswerRequest(aqId, null, payload, seq, null);
    }

    private JsonNode single(String key) {
        try {
            return objectMapper.readTree("{\"selectedOptionKey\":\"" + key + "\"}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE attempt_id=?", Long.class, attemptId);
    }

    private Instant lastSavedAt() {
        return jdbc.queryForObject("SELECT last_saved_at FROM attempts WHERE id=?", Instant.class, attemptId);
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
