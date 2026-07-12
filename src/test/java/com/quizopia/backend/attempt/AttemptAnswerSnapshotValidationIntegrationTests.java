package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AnswerPayloadValidator;
import com.quizopia.backend.attempt.application.AttemptAutosaveService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.SaveAnswerRequest;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Strict snapshot option-order validation tests (A3.2-3R2 §4). The persisted {@code option_order}
 * snapshot is validated STRICTLY before the payload (including clear). Any corruption →
 * {@code ATTEMPT_VALIDATION_ERROR}, NO AttemptAnswer row created/updated, and {@code attempts.last_saved_at}
 * unchanged. No silent repair, no partial result. Also covers valid regressions.
 *
 * <p>The validator under test is {@link AnswerPayloadValidator}; these tests exercise it end-to-end
 * through the production {@link AttemptAutosaveService} so the no-mutation guarantees are real.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptAnswerSnapshotValidationIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private AnswerPayloadValidator validator;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;
    @Autowired private ObjectMapper objectMapper;

    private long userId;
    private long attemptId;
    private long aqSingleId;
    private long aqMultipleId;
    private long aqTfId;
    private long aqNumericId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        userId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sv','sv@t.com','h','SV')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SVS','Sch')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + userId + "," + school + ",'TC')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + userId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',4,now()," + userId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long eqSingle = eq(ver, sec, bank, "SINGLE_CHOICE", 0);
        opts(eqSingle);
        long eqMultiple = eq(ver, sec, bank, "MULTIPLE_CHOICE", 1);
        opts(eqMultiple);
        long eqTf = eq(ver, sec, bank, "TRUE_FALSE_MATRIX", 2);
        opts(eqTf);
        long eqNumeric = eqNumeric(ver, sec, bank, 3);
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + userId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + userId + ")");
        attemptId = attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = aqOf(eqSingle);
        aqMultipleId = aqOf(eqMultiple);
        aqTfId = aqOf(eqTf);
        aqNumericId = aqOf(eqNumeric);
    }

    // === SINGLE_CHOICE snapshot corruptions ===
    // NOTE: the V9 CHECK chk_attempt_questions_option_order only enforces jsonb_typeof = 'array' (or NULL),
    // so cardinality/duplicate/non-string/out-of-range corruptions ARE persistable and tested end-to-end
    // through the service below. JSON-null and non-array option_order (and unknown question_type) are
    // rejected by the DB CHECK itself, so those specific corruptions are tested directly against the
    // validator (the layer that enforces them at runtime when the DB allows them through).

    @Test void singleSqlNullOrderRejected() { assertSqlNullOrderRejected(aqSingleId, single("A")); }
    @Test void singleEmptyArrayRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[]", single("A")); }
    @Test void singleDuplicateRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[\"A\",\"A\",\"B\",\"C\"]", single("A")); }
    @Test void singleNonStringElementRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[1,2,3,4]", single("A")); }
    @Test void singleThreeKeysRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[\"A\",\"B\",\"C\"]", single("A")); }
    @Test void singleSevenKeysRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\",\"G\"]", single("A")); }
    @Test void singleKeyOutsideRangeRejected() { assertCorruptedSnapshotRejected(aqSingleId, "[\"A\",\"B\",\"C\",\"G\"]", single("A")); }

    // --- DB-CHECK-rejected corruptions: exercised directly against the validator ---
    @Test void singleJsonNullOrderRejectedByValidator() {
        assertThatThrownBy(() -> validator.validateAndCanonicalize("SINGLE_CHOICE", json("null"), single("A")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }
    @Test void singleNonArrayOrderRejectedByValidator() {
        assertThatThrownBy(() -> validator.validateAndCanonicalize("SINGLE_CHOICE", json("\"x\""), single("A")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    // === MULTIPLE_CHOICE snapshot corruption ===
    @Test void multipleCorruptedSnapshotRejected() { assertCorruptedSnapshotRejected(aqMultipleId, "[\"A\",\"A\"]", json("{\"selectedOptionKeys\":[\"A\"]}")); }

    // === TRUE_FALSE_MATRIX snapshot corruptions ===
    @Test void tfMissingKeyRejected() { assertCorruptedSnapshotRejected(aqTfId, "[\"A\",\"B\",\"C\"]", tfPayload()); }
    @Test void tfDuplicateRejected() { assertCorruptedSnapshotRejected(aqTfId, "[\"A\",\"A\",\"B\",\"C\"]", tfPayload()); }
    @Test void tfKeyOutsideRangeRejected() { assertCorruptedSnapshotRejected(aqTfId, "[\"A\",\"B\",\"C\",\"E\"]", tfPayload()); }
    @Test void tfWrongSetRejected() { assertCorruptedSnapshotRejected(aqTfId, "[\"A\",\"B\",\"D\",\"E\"]", tfPayload()); }

    // === NUMERIC_FILL snapshot corruption (option_order must be null) ===
    @Test void numericNonNullOrderRejected() { assertCorruptedSnapshotRejected(aqNumericId, "[\"A\"]", numericPayload()); }

    // === unknown questionType (DB CHECK chk_attempt_questions_type limits to the 4 types, so exercised
    //     directly against the validator — the runtime layer that rejects it) ===
    @Test void unknownQuestionTypeRejectedByValidator() {
        assertThatThrownBy(() -> validator.validateAndCanonicalize("WEIRD_TYPE", json("[\"A\",\"B\",\"C\",\"D\"]"), single("A")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    // === clear payload with corrupted snapshot is STILL rejected ===
    @Test void clearPayloadWithCorruptedSnapshotRejected() {
        // Baseline: one accepted save so last_saved_at is non-null, then corrupt + clear → must not change it.
        autosaveService.saveAnswer(userId, attemptId, req(aqSingleId, single("A")));
        em.clear();
        Instant baseline = lastSavedAt();
        jdbc.update("UPDATE attempt_questions SET option_order = '[\"A\",\"A\",\"B\",\"C\"]'::jsonb WHERE id=" + aqSingleId);
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(userId, attemptId, req(aqSingleId, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        assertPayloadIs(aqSingleId, "A"); // prior answer intact
        assertThat(lastSavedAt()).isEqualTo(baseline); // unchanged
    }

    // === valid regressions ===

    @Test void singleFourKeysValid() {
        assertThat(autosaveService.saveAnswer(userId, attemptId, req(aqSingleId, single("B"))).accepted()).isTrue();
    }

    @Test void singleSixKeysValid() {
        // option_order can carry up to 6 A–F keys; the validator accepts 6 distinct.
        jdbc.update("UPDATE attempt_questions SET option_order = '[\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"]'::jsonb WHERE id=" + aqSingleId);
        em.clear();
        assertThat(autosaveService.saveAnswer(userId, attemptId, req(aqSingleId, single("F"))).accepted()).isTrue();
    }

    @Test void multipleFourToSixValid() {
        assertThat(autosaveService.saveAnswer(userId, attemptId, req(aqMultipleId, json("{\"selectedOptionKeys\":[\"A\",\"C\"]}"))).accepted()).isTrue();
    }

    @Test void tfReorderedPersistedOrderValid() {
        // Persisted order different from A,B,C,D but the SET is exactly {A,B,C,D} → still valid.
        jdbc.update("UPDATE attempt_questions SET option_order = '[\"D\",\"C\",\"B\",\"A\"]'::jsonb WHERE id=" + aqTfId);
        em.clear();
        assertThat(autosaveService.saveAnswer(userId, attemptId, req(aqTfId, tfPayload())).accepted()).isTrue();
    }

    @Test void numericNullOrderValid() {
        assertThat(autosaveService.saveAnswer(userId, attemptId, req(aqNumericId, numericPayload())).accepted()).isTrue();
    }

    // === helpers ===

    private void assertCorruptedSnapshotRejected(long aqId, String optionOrderJson, JsonNode payload) {
        Instant baseline = lastSavedAt();
        jdbc.update("UPDATE attempt_questions SET option_order = ?::jsonb WHERE id = ?", optionOrderJson, aqId);
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(userId, attemptId, new SaveAnswerRequest(aqId, null, payload, 1, null)))
                .as("corrupted option_order %s", optionOrderJson)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        assertNoAnswer(aqId);
        assertThat(lastSavedAt()).as("last_saved_at must not change on rejected save").isEqualTo(baseline);
    }

    private void assertSqlNullOrderRejected(long aqId, JsonNode payload) {
        Instant baseline = lastSavedAt();
        jdbc.update("UPDATE attempt_questions SET option_order = NULL WHERE id = " + aqId);
        em.clear();
        assertThatThrownBy(() -> autosaveService.saveAnswer(userId, attemptId, new SaveAnswerRequest(aqId, null, payload, 1, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
        assertNoAnswer(aqId);
        assertThat(lastSavedAt()).isEqualTo(baseline);
    }

    private SaveAnswerRequest req(long aqId, JsonNode payload) {
        return new SaveAnswerRequest(aqId, null, payload, 1, null);
    }

    private JsonNode single(String key) { return json("{\"selectedOptionKey\":\"" + key + "\"}"); }
    private JsonNode tfPayload() { return json("{\"responses\":{\"A\":true,\"B\":false,\"C\":true,\"D\":false}}"); }
    private JsonNode numericPayload() { return json("{\"value\":\"1.25\"}"); }
    private JsonNode json(String text) { try { return objectMapper.readTree(text); } catch (Exception e) { throw new RuntimeException(e); } }

    private long aqOf(long eqId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=? AND exam_question_id=?", Long.class, attemptId, eqId);
    }

    private void assertNoAnswer(long aqId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_question_id=?", Integer.class, aqId);
        assertThat(count).as("no AttemptAnswer row may exist for a rejected save").isEqualTo(0);
    }

    private void assertPayloadIs(long aqId, String key) {
        String stored = jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqId);
        assertThat(stored).isEqualTo(key);
    }

    private Instant lastSavedAt() {
        return jdbc.queryForObject("SELECT last_saved_at FROM attempts WHERE id=?", Instant.class, attemptId);
    }

    private static String code(Throwable e) {
        return ((com.quizopia.backend.attempt.exception.AttemptException) e).getErrorCode().code();
    }

    private long eq(long ver, long sec, long bank, String type, int position) {
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + userId + ")");
        return ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long eqNumeric(long ver, long sec, long bank, int position) {
        // V7 CHECK chk_question_versions_numeric_answer_key requires expectedAnswer(string) +
        // requiredInputLength(=4) + non-blank roundingInstruction.
        String key = "'{\"expectedAnswer\":\"1.25\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN" + position + "'," + userId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + q + ",1,'NUMERIC_FILL','n',1,'{}'::jsonb," + key + "," + userId + ")");
        return ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','NUMERIC_FILL','n',1," + position + ",'{}'::jsonb," + key + ")");
    }

    private void opts(long eqId) {
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES "
                + "(" + eqId + ",'A','a',false,0),(" + eqId + ",'B','b',false,1),(" + eqId + ",'C','c',true,2),(" + eqId + ",'D','d',false,3)");
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
