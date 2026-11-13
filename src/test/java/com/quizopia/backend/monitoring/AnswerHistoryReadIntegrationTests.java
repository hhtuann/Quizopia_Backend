package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AnswerHistoryService;
import com.quizopia.backend.monitoring.dto.AnswerHistoryResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code GET /api/attempts/{attemptId}/answer-history}
 * (P4-A3, issue #82): the accepted-answer history read side — one row per
 * SERVER-ACCEPTED autosave sequence, ordered (accepted_at, id) ascending;
 * authorization mirrors the timeline (attempt owner OR session-owner teacher
 * with EXAM_SESSION_MONITOR); the attemptQuestionId filter narrows to one
 * question's version chain.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AnswerHistoryReadIntegrationTests {

    @Autowired private AnswerHistoryService historyService;
    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;

    private Instant base;
    private long studentUserId;
    private long teacherUserId;
    private long attemptId;
    private long aqSingleId;
    private long aqMultipleId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(base);
        studentUserId = newUser("aht", "STUDENT");
        teacherUserId = newUser("ahtc", "TEACHER");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','Math')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subj + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + teacherUserId + "," + subj + ",'E','Exam','PUBLISHED',now(),2)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eqSingle = eq(exam, sec, bank, "SINGLE_CHOICE", 0);
        long eqMultiple = eq(exam, sec, bank, "MULTIPLE_CHOICE", 1);
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqSingle + ",'A','a',false,0),(" + eqSingle + ",'B','b',false,1),(" + eqSingle + ",'C','c',true,2),(" + eqSingle + ",'D','d',false,3)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqMultiple + ",'A','a',false,0),(" + eqMultiple + ",'B','b',false,1),(" + eqMultiple + ",'C','c',true,2),(" + eqMultiple + ",'D','d',false,3)");
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'S','Sess','OPEN','" + base.minusSeconds(3600) + "','" + base.plusSeconds(7200) + "',2," + teacherUserId + ",'" + base.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + session + "," + teacherUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = aqId(eqSingle);
        aqMultipleId = aqId(eqMultiple);
    }

    // === reads ===

    @Test void ownerReadsTwoAcceptedSavesAscending() {
        autosave(aqSingleId, single("A"), 1);
        clock.advance(java.time.Duration.ofSeconds(5));
        autosave(aqSingleId, single("B"), 5);

        AnswerHistoryResponse r = historyService.history(studentUserId, attemptId, null, null);
        assertThat(r.attemptId()).isEqualTo(attemptId);
        assertThat(r.totalCount()).isEqualTo(2);
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).attemptQuestionId()).isEqualTo(aqSingleId);
        assertThat(r.items().get(0).acceptedSequence()).isEqualTo(1L);
        assertThat(r.items().get(1).acceptedSequence()).isEqualTo(5L);
        assertThat(r.items()).as("(accepted_at, id) ascending")
                .isSortedAccordingTo(java.util.Comparator.comparing(AnswerHistoryResponse.AnswerHistoryItem::acceptedAt));
        assertThat(r.items().get(0).answerPayload().get("selectedOptionKey").asString()).isEqualTo("A");
        assertThat(r.items().get(1).answerPayload().get("selectedOptionKey").asString()).isEqualTo("B");
        assertThat(r.items()).allSatisfy(i -> assertThat(i.acceptedAt()).isNotNull());
    }

    @Test void sessionOwnerTeacherReads() {
        autosave(aqSingleId, single("A"), 1);
        autosave(aqSingleId, single("C"), 2);
        AnswerHistoryResponse r = historyService.history(teacherUserId, attemptId, null, null);
        assertThat(r.totalCount()).isEqualTo(2);
        assertThat(r.items()).extracting(AnswerHistoryResponse.AnswerHistoryItem::acceptedSequence)
                .containsExactly(1L, 2L);
    }

    @Test void attemptQuestionIdFilterNarrowsToThatQuestion() {
        autosave(aqSingleId, single("A"), 1);
        autosave(aqSingleId, single("B"), 2);
        autosave(aqMultipleId, multiple("A", "C"), 1);

        AnswerHistoryResponse all = historyService.history(studentUserId, attemptId, null, null);
        assertThat(all.totalCount()).isEqualTo(3);

        AnswerHistoryResponse filtered = historyService.history(studentUserId, attemptId, aqSingleId, null);
        assertThat(filtered.totalCount()).isEqualTo(2);
        assertThat(filtered.items()).allSatisfy(i -> assertThat(i.attemptQuestionId()).isEqualTo(aqSingleId));
        assertThat(filtered.items()).extracting(AnswerHistoryResponse.AnswerHistoryItem::acceptedSequence)
                .containsExactly(1L, 2L);
    }

    // === authorization ===

    @Test void otherTeacherRejected403() {
        long other = newUser("ahot", "TEACHER");
        assertThatThrownBy(() -> historyService.history(other, attemptId, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    @Test void foreignStudentRejected403() {
        long other = newUser("ahfs", "STUDENT");
        assertThatThrownBy(() -> historyService.history(other, attemptId, null, null))
                .isInstanceOf(MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_ACCESS_DENIED"));
    }

    // === helpers ===

    private void autosave(long aqId, JsonNode payload, long seq) {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId,
                new SaveAnswerRequest(aqId, null, payload, seq, null)).accepted()).isTrue();
    }

    private JsonNode single(String key) {
        return objectMapper.readTree("{\"selectedOptionKey\":\"" + key + "\"}");
    }

    private JsonNode multiple(String... keys) {
        return objectMapper.readTree("{\"selectedOptionKeys\":[\"" + String.join("\",\"", keys) + "\"]}");
    }

    private long aqId(long eqId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?", Long.class, attemptId, eqId);
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

    private static String code(Throwable e) {
        return ((MonitoringException) e).getErrorCode().code();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
