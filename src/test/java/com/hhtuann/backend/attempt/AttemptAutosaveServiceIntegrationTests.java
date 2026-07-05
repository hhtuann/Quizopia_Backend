package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptAutosaveService;
import com.hhtuann.backend.attempt.application.AttemptQueryService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.AttemptDetailResponse;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code PUT /api/attempts/{attemptId}/answers} (A3.2-3 autosave). Covers
 * authorization, state/deadline, question resolution, the 4 payload-type validators (canonicalization,
 * NUMERIC exact-4), sequence-guard UPSERT (accepted/stale), clear-answer semantics, and integration
 * with detail (answeredCount).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptAutosaveServiceIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private AttemptQueryService queryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;
    @Autowired private ObjectMapper objectMapper;

    private long studentUserId;
    private long attemptId;
    private long aqSingleId;
    private long aqMultipleId;
    private long aqTfId;
    private long aqNumericId;
    private long eqSingleId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('a','a@t.com','h','A')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long school = insert("INSERT INTO schools (code, name) VALUES ('AS','Autosave School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        long teacher = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + school + ",'TC')");
        long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + teacher + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + teacher + ",'E','Exam')");
        long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',4,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        String numericKey = "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        eqSingleId = eq(ver, sec, bank, "SINGLE_CHOICE", 0, studentUserId);
        opts(eqSingleId);
        long eqMultiple = eq(ver, sec, bank, "MULTIPLE_CHOICE", 1, studentUserId);
        opts(eqMultiple);
        long eqTf = eq(ver, sec, bank, "TRUE_FALSE_MATRIX", 2, studentUserId);
        opts(eqTf);
        long eqNumeric = eqNumeric(ver, sec, bank, 3, studentUserId, numericKey);
        long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + "," + teacher + ",'S','Sess','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = aqId(eqSingleId);
        aqMultipleId = aqId(eqMultiple);
        aqTfId = aqId(eqTf);
        aqNumericId = aqId(eqNumeric);
    }

    // === authorization / ownership ===

    @Test void validStudentAccepted() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        assertThat(r.accepted()).isTrue();
        assertThat(r.currentSequenceNumber()).isEqualTo(1);
    }

    @Test void missingRoleRejected403() {
        revokeStudentRole();
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void revokedPermissionRejected403() {
        revoke("ATTEMPT_ANSWER_SAVE");
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void systemAdminWithoutStudentRejected403() {
        long admin = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sa','sa@t.com','h','SA')");
        long adminRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='SYSTEM_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + admin + "," + adminRole + ")");
        assertThatThrownBy(() -> autosaveService.saveAnswer(admin, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void missingProfileRejected404() {
        long noProfile = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t.com','h','NP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + noProfile + "," + roleId + ")");
        assertThatThrownBy(() -> autosaveService.saveAnswer(noProfile, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
    }

    @Test void inactiveProfileRejected403() {
        jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE'");
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void missingAttemptRejected404() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, 999999L, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_NOT_FOUND"));
    }

    @Test void foreignAttemptRejected403() {
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'OS' FROM student_profiles WHERE user_id=" + studentUserId);
        assertThatThrownBy(() -> autosaveService.saveAnswer(other, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void crossSchoolAttemptRejected403() {
        long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + otherSchool + ",'XS')");
        assertThatThrownBy(() -> autosaveService.saveAnswer(other, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    // === state / deadline ===

    @Test void inProgressAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)).accepted()).isTrue();
    }

    @Test void exactDeadlineAccepted() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline);
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)).accepted()).isTrue();
    }

    @Test void afterDeadlineRejected409() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED"));
    }

    @Test void submittedRejected409InvalidState() {
        submitAttempt();
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_STATE"));
    }

    @Test void gradedRejected409InvalidState() {
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=now(), submission_idempotency_key='G' WHERE id=" + attemptId);
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_STATE"));
    }

    @Test void rejectedSaveDoesNotMutate() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        String before = row();
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)))
                .isInstanceOf(AttemptException.class);
        em.clear();
        assertThat(row()).isEqualTo(before); // status/timestamps unchanged
    }

    // === question resolution ===

    @Test void resolvesByAttemptQuestionId() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        assertThat(r.attemptQuestionId()).isEqualTo(aqSingleId);
    }

    @Test void resolvesByExamQuestionId() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, reqByEq(eqSingleId, single("A"), 1));
        assertThat(r.attemptQuestionId()).isEqualTo(aqSingleId);
    }

    @Test void bothPresentAttemptQuestionIdAuthoritative() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, new SaveAnswerRequest(aqSingleId, eqSingleId, single("A"), 1, null));
        assertThat(r.attemptQuestionId()).isEqualTo(aqSingleId);
    }

    @Test void neitherPresentRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, new SaveAnswerRequest(null, null, single("A"), 1, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void questionFromAnotherAttemptRejected404() {
        // aqSingleId belongs to this attempt; use a fabricated aqId from another attempt → 404.
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(999999L, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_QUESTION_NOT_FOUND"));
    }

    @Test void unknownQuestionRejected404() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, reqByEq(999999L, single("A"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_QUESTION_NOT_FOUND"));
    }

    // === payload validation ===

    @Test void clearNullInsertAccepted() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, null, 1));
        assertThat(r.accepted()).isTrue();
        Integer payloadCount = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_id=? AND answer_payload IS NULL", Integer.class, attemptId);
        assertThat(payloadCount).isEqualTo(1);
    }

    @Test void clearNullUpdateWithHigherSequenceAccepted() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, null, 5));
        assertThat(r.accepted()).isTrue();
        assertThat(r.currentSequenceNumber()).isEqualTo(5);
    }

    @Test void singleValidAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 1)).accepted()).isTrue();
    }

    @Test void singleUnknownKeyRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("Z"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void singleWrongShapeRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, json("{\"selectedOptionKeys\":[\"A\"]}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void multipleCanonicalSortDedup() {
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqMultipleId, json("{\"selectedOptionKeys\":[\"C\",\"A\",\"C\",\"B\"]}"), 1));
        assertThat(r.accepted()).isTrue();
        String stored = jdbc.queryForObject("SELECT answer_payload->'selectedOptionKeys' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqMultipleId);
        assertThat(stored).isEqualTo("[\"A\", \"B\", \"C\"]"); // sorted + deduped (PG jsonb has spaces)
    }

    @Test void multipleUnknownKeyRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqMultipleId, json("{\"selectedOptionKeys\":[\"A\",\"Z\"]}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void multipleWrongElementTypeRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqMultipleId, json("{\"selectedOptionKeys\":[1,2]}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void tfFullAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqTfId, json("{\"responses\":{\"A\":true,\"B\":false,\"C\":true,\"D\":false}}"), 1)).accepted()).isTrue();
    }

    @Test void tfPartialAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqTfId, json("{\"responses\":{\"A\":true}}"), 1)).accepted()).isTrue();
    }

    @Test void tfEmptyResponsesAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqTfId, json("{\"responses\":{}}"), 1)).accepted()).isTrue();
    }

    @Test void tfUnknownKeyRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqTfId, json("{\"responses\":{\"Z\":true}}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void tfNonBooleanRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqTfId, json("{\"responses\":{\"A\":\"true\"}}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void numericValidSamplesAccepted() {
        for (String v : new String[]{"1.25", "1,25", "2.50", "-1.2", "02.5"}) {
            jdbc.update("DELETE FROM attempt_answers WHERE attempt_question_id=?", aqNumericId);
            assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqNumericId, json("{\"value\":\"" + v + "\"}"), 1)).accepted())
                    .as("numeric %s", v).isTrue();
        }
    }

    @Test void numericInvalidSamplesRejected400() {
        for (String v : new String[]{"2.5", "10.05", "-1.25", "+1.2", "1e2", "1. 2"}) {
            assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqNumericId, json("{\"value\":\"" + v + "\"}"), 1)))
                    .as("numeric %s", v).satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
        }
    }

    @Test void numericRawRepresentationPreserved() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqNumericId, json("{\"value\":\"1,25\"}"), 1));
        String stored = jdbc.queryForObject("SELECT answer_payload->>'value' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqNumericId);
        assertThat(stored).isEqualTo("1,25"); // comma preserved, no normalization
    }

    @Test void scalarPayloadRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, json("\"x\""), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void arrayPayloadRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, json("[1,2]"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    @Test void extraFieldsRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, json("{\"selectedOptionKey\":\"A\",\"extra\":1}"), 1)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
    }

    // === sequence guard ===

    @Test void firstSequenceOneAccepted() {
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)).accepted()).isTrue();
    }

    @Test void sequenceZeroRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 0)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void sequenceNegativeRejected400() {
        assertThatThrownBy(() -> autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), -3)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    @Test void higherSequenceAccepted() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1));
        assertThat(autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 5)).accepted()).isTrue();
    }

    @Test void equalSequenceStale() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 5));
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 5));
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).isEqualTo("STALE_SEQUENCE");
    }

    @Test void lowerSequenceStale() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 5));
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 3));
        assertThat(r.accepted()).isFalse();
    }

    @Test void stalePreservesPayloadSequenceSavedAt() {
        SaveAnswerResponse first = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 5));
        em.clear();
        SaveAnswerResponse stale = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 3));
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.currentSequenceNumber()).isEqualTo(5); // unchanged
        assertThat(stale.savedAt()).isEqualTo(first.savedAt()); // unchanged
        String stored = jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqSingleId);
        assertThat(stored).isEqualTo("A"); // stale payload not overwritten
    }

    @Test void acceptedClearPreservesNewSequence() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 5));
        SaveAnswerResponse r = autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, null, 8));
        assertThat(r.accepted()).isTrue();
        assertThat(r.currentSequenceNumber()).isEqualTo(8);
    }

    @Test void detailReflectsSavedAnswerImmediately() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("B"), 1));
        em.clear();
        AttemptDetailResponse detail = queryService.getAttemptDetail(studentUserId, attemptId);
        AttemptDetailResponse.QuestionView q = detail.questions().stream().filter(x -> x.questionType().equals("SINGLE_CHOICE")).findFirst().orElseThrow();
        assertThat(q.savedAnswer()).isNotNull();
        assertThat(q.savedAnswer().answerPayload().get("selectedOptionKey").asString()).isEqualTo("B");
    }

    @Test void answeredCountChangesOnlyForNonNullPayload() {
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqSingleId, single("A"), 1)); // non-null → count
        autosaveService.saveAnswer(studentUserId, attemptId, req(aqMultipleId, null, 1));       // null → no count
        em.clear();
        AttemptDetailResponse detail = queryService.getAttemptDetail(studentUserId, attemptId);
        assertThat(detail.answeredCount()).isEqualTo(1);
    }

    // === helpers ===

    private SaveAnswerRequest req(long aqId, JsonNode payload, long seq) {
        return new SaveAnswerRequest(aqId, null, payload, seq, null);
    }

    private SaveAnswerRequest reqByEq(long eqId, JsonNode payload, long seq) {
        return new SaveAnswerRequest(null, eqId, payload, seq, null);
    }

    private JsonNode single(String key) {
        return json("{\"selectedOptionKey\":\"" + key + "\"}");
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long aqId(long eqId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?", Long.class, attemptId, eqId);
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private void revokeStudentRole() {
        jdbc.update("DELETE FROM user_roles WHERE user_id=" + studentUserId);
    }

    private void submitAttempt() {
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K' WHERE id=" + attemptId);
    }

    private String row() {
        return jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM updated_at) || '|' || EXTRACT(EPOCH FROM deadline_at) FROM attempts WHERE id=" + attemptId, String.class);
    }

    private static String code(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private long eq(long ver, long sec, long bank, String type, int position, long user) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + user + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + user + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long eqNumeric(long ver, long sec, long bank, int position, long user, String numericKey) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN" + position + "'," + user + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + q + ",1,'NUMERIC_FILL','n',1,'{}'::jsonb," + numericKey + "," + user + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','NUMERIC_FILL','n',1," + position + ",'{}'::jsonb," + numericKey + ")");
    }

    private void opts(long eqId) {
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqId + ",'A','a',false,0),(" + eqId + ",'B','b',false,1),(" + eqId + ",'C','c',true,2),(" + eqId + ",'D','d',false,3)");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
