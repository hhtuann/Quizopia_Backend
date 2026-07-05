package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptAutosaveService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.SaveAnswerRequest;
import com.hhtuann.backend.attempt.dto.SaveAnswerResponse;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.attempt.exception.AttemptException;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Raw-JDBC metadata + no-mutation tests for autosave (A3.2-3R2 §5/§6/§10). Covers:
 * <ul>
 *   <li>§5 — {@code attempts.last_saved_at} equals {@code attempt_answers.saved_at} on accepted insert/update,
 *       unchanged on stale/rejected; attempt status/deadline/submittedAt/idempotencyKey/clientInstanceId never
 *       mutated by accepted or stale saves.</li>
 *   <li>§6 — request {@code clientInstanceId} is request-only (V9 has no attempt_answers.client_instance_id):
 *       a different UUID in the request does NOT overwrite {@code attempts.client_instance_id}.</li>
 *   <li>§10 — no Grade/GradeItem/IdempotencyRecord created, no new Attempt/AttemptQuestion, no event source.</li>
 * </ul>
 *
 * <p>Class is {@code NOT_SUPPORTED}: each {@code autosaveService.saveAnswer} call then runs in its OWN
 * transaction, so PG {@code CURRENT_TIMESTAMP} (transaction-start time) differs between calls — letting us
 * assert {@code saved_at} actually advances on a higher-sequence update. All assertions are raw JDBC so they
 * read committed state, not the persistence-context cache.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttemptAutosaveMetadataIntegrationTests {

    @Autowired private AttemptAutosaveService autosaveService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;

    private TransactionTemplate tx() { return new TransactionTemplate(txm); }

    // === §5 last_saved_at semantics ===

    @Test
    void acceptedInsertSetsLastSavedAtEqualToSavedAt() {
        Chain c = setup(null);
        try {
            SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            assertThat(r.accepted()).isTrue();
            Instant answerSavedAt = answerSavedAt(c.aqId);
            Instant lastSavedAt = lastSavedAt(c.attemptId);
            assertThat(lastSavedAt).isNotNull();
            assertThat(lastSavedAt).as("last_saved_at must equal the answer saved_at on first accepted save").isEqualTo(answerSavedAt);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void acceptedHigherSequenceUpdatesLastSavedAt() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Instant firstLastSaved = lastSavedAt(c.attemptId);
            Instant firstAnswerSaved = answerSavedAt(c.aqId);
            // Higher-sequence update runs in its own tx → saved_at advances past the first.
            SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
            assertThat(r.accepted()).isTrue();
            Instant newAnswerSaved = answerSavedAt(c.aqId);
            Instant newLastSaved = lastSavedAt(c.attemptId);
            assertThat(newAnswerSaved).as("saved_at must advance on a higher-sequence update").isAfter(firstAnswerSaved);
            assertThat(newLastSaved).isEqualTo(newAnswerSaved);
            assertThat(newLastSaved).isAfter(firstLastSaved);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void staleEqualSequencePreservesLastSavedAt() {
        Chain c = setup(null);
        try {
            SaveAnswerResponse first = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 5));
            Instant baselineLastSaved = lastSavedAt(c.attemptId);
            Instant baselineAnswerSaved = answerSavedAt(c.aqId);
            SaveAnswerResponse stale = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5));
            assertThat(stale.accepted()).isFalse();
            assertThat(stale.savedAt()).isEqualTo(first.savedAt()); // unchanged
            assertThat(answerPayload(c.aqId)).isEqualTo("A"); // stale payload not overwritten
            assertThat(answerSeq(c.aqId)).isEqualTo(5);
            assertThat(answerSavedAt(c.aqId)).isEqualTo(baselineAnswerSaved);
            assertThat(lastSavedAt(c.attemptId)).as("stale must not change last_saved_at").isEqualTo(baselineLastSaved);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void staleLowerSequencePreservesLastSavedAt() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 5));
            Instant baseline = lastSavedAt(c.attemptId);
            SaveAnswerResponse stale = autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 3));
            assertThat(stale.accepted()).isFalse();
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
            assertThat(answerSeq(c.aqId)).isEqualTo(5);
        } finally { cleanup(c.attemptId); }
    }

    // === §5 rejected saves leave last_saved_at unchanged ===

    @Test
    void rejectedStateLeavesLastSavedAtUnchanged() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1)); // baseline
            Instant baseline = lastSavedAt(c.attemptId);
            jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K' WHERE id=" + c.attemptId);
            assertThatThrownBy(() -> autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_STATE"));
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void rejectedDeadlineLeavesLastSavedAtUnchanged() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Instant baseline = lastSavedAt(c.attemptId);
            Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + c.attemptId, Instant.class);
            clock.setInstant(deadline.plusSeconds(1));
            assertThatThrownBy(() -> autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED"));
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void rejectedPayloadLeavesLastSavedAtUnchanged() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Instant baseline = lastSavedAt(c.attemptId);
            assertThatThrownBy(() -> autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "Z", 5)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_INVALID_ANSWER_PAYLOAD"));
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void rejectedCorruptedSnapshotLeavesLastSavedAtUnchanged() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Instant baseline = lastSavedAt(c.attemptId);
            jdbc.update("UPDATE attempt_questions SET option_order = '[\"A\",\"A\",\"B\",\"C\"]'::jsonb WHERE id=" + c.aqId);
            assertThatThrownBy(() -> autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 5)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void rejectedOwnershipLeavesLastSavedAtUnchanged() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Instant baseline = lastSavedAt(c.attemptId);
            long other = foreignStudent(c.schoolId);
            assertThatThrownBy(() -> autosaveService.saveAnswer(other, c.attemptId, req(c.aqId, "B", 5)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
            assertThat(lastSavedAt(c.attemptId)).isEqualTo(baseline);
        } finally { cleanup(c.attemptId); }
    }

    // === §5 attempt metadata immutability ===

    @Test
    void acceptedSaveDoesNotMutateAttemptMetadata() {
        Chain c = setup(UUID.randomUUID());
        try {
            String before = attemptMetadata(c.attemptId);
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            assertThat(attemptMetadata(c.attemptId)).as("status/deadline/submittedAt/key/clientInstanceId must be unchanged").isEqualTo(before);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void staleSaveDoesNotMutateAttemptMetadata() {
        Chain c = setup(UUID.randomUUID());
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 5));
            String before = attemptMetadata(c.attemptId);
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "B", 3)); // stale
            assertThat(attemptMetadata(c.attemptId)).isEqualTo(before);
        } finally { cleanup(c.attemptId); }
    }

    // === §6 client instance contract ===

    @Test
    void requestClientInstanceIdDoesNotOverwriteAttempt() {
        UUID attemptClient = UUID.randomUUID();
        Chain c = setup(attemptClient);
        try {
            // Autosave with a DIFFERENT clientInstanceId in the request.
            SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId,
                    new SaveAnswerRequest(c.aqId, null, single("A"), 1, UUID.randomUUID()));
            assertThat(r.accepted()).isTrue();
            UUID stored = jdbc.queryForObject("SELECT client_instance_id FROM attempts WHERE id=?", UUID.class, c.attemptId);
            assertThat(stored).as("request clientInstanceId must NOT overwrite attempts.client_instance_id").isEqualTo(attemptClient);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void acceptedWithClientInstanceId() {
        Chain c = setup(null);
        try {
            SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId,
                    new SaveAnswerRequest(c.aqId, null, single("A"), 1, UUID.randomUUID()));
            assertThat(r.accepted()).isTrue();
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void staleWithClientInstanceId() {
        Chain c = setup(null);
        try {
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 5));
            SaveAnswerResponse r = autosaveService.saveAnswer(c.userId, c.attemptId,
                    new SaveAnswerRequest(c.aqId, null, single("B"), 3, UUID.randomUUID()));
            assertThat(r.accepted()).isFalse();
        } finally { cleanup(c.attemptId); }
    }

    // === §10 no grade / idempotency / event / new attempt-question ===

    @Test
    void acceptedCreatesNoGradeIdempotencyOrExtraRows() {
        Chain c = setup(null);
        try {
            Counts before = counts(c);
            autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "A", 1));
            Counts after = counts(c);
            assertThat(after.grades).as("no Grade created").isEqualTo(before.grades);
            assertThat(after.gradeItems).as("no GradeItem created").isEqualTo(before.gradeItems);
            assertThat(after.idempotencyRecords).as("no IdempotencyRecord created").isEqualTo(before.idempotencyRecords);
            assertThat(after.attempts).as("no new Attempt created").isEqualTo(before.attempts);
            assertThat(after.attemptQuestions).as("no new AttemptQuestion created").isEqualTo(before.attemptQuestions);
            assertThat(after.answers).as("exactly one answer row for this question").isEqualTo(before.answers + 1);
        } finally { cleanup(c.attemptId); }
    }

    @Test
    void rejectedCreatesNoGradeIdempotencyOrExtraRows() {
        Chain c = setup(null);
        try {
            Counts before = counts(c);
            assertThatThrownBy(() -> autosaveService.saveAnswer(c.userId, c.attemptId, req(c.aqId, "Z", 1)))
                    .isInstanceOf(AttemptException.class);
            Counts after = counts(c);
            assertThat(after.grades).isEqualTo(before.grades);
            assertThat(after.gradeItems).isEqualTo(before.gradeItems);
            assertThat(after.idempotencyRecords).isEqualTo(before.idempotencyRecords);
            assertThat(after.attempts).isEqualTo(before.attempts);
            assertThat(after.attemptQuestions).isEqualTo(before.attemptQuestions);
            assertThat(after.answers).as("no answer row on rejected save").isEqualTo(before.answers);
        } finally { cleanup(c.attemptId); }
    }

    // === helpers ===

    private record Chain(long userId, long schoolId, long attemptId, long aqId) {}

    private record Counts(long attempts, long attemptQuestions, long answers, long grades, long gradeItems, long idempotencyRecords) {}

    private SaveAnswerRequest req(long aqId, String optionKey, long seq) {
        return new SaveAnswerRequest(aqId, null, single(optionKey), seq, null);
    }

    private JsonNode single(String key) {
        return objectMapper.createObjectNode().put("selectedOptionKey", key);
    }

    private Instant lastSavedAt(long attemptId) {
        return jdbc.queryForObject("SELECT last_saved_at FROM attempts WHERE id=?", Instant.class, attemptId);
    }

    private Instant answerSavedAt(long aqId) {
        return jdbc.queryForObject("SELECT saved_at FROM attempt_answers WHERE attempt_question_id=?", Instant.class, aqId);
    }

    private long answerSeq(long aqId) {
        return jdbc.queryForObject("SELECT sequence_number FROM attempt_answers WHERE attempt_question_id=?", Long.class, aqId);
    }

    private String answerPayload(long aqId) {
        return jdbc.queryForObject("SELECT answer_payload->>'selectedOptionKey' FROM attempt_answers WHERE attempt_question_id=?", String.class, aqId);
    }

    private String attemptMetadata(long attemptId) {
        // status | deadline_at | submitted_at | submission_idempotency_key | client_instance_id
        return jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM deadline_at) || '|' || "
                + "COALESCE(EXTRACT(EPOCH FROM submitted_at)::text, 'null') || '|' || "
                + "COALESCE(submission_idempotency_key, 'null') || '|' || COALESCE(client_instance_id::text, 'null') "
                + "FROM attempts WHERE id=?", String.class, attemptId);
    }

    private Counts counts(Chain c) {
        long attempts = jdbc.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, c.attemptId);
        long attemptQuestions = jdbc.queryForObject("SELECT count(*) FROM attempt_questions WHERE attempt_id=?", Long.class, c.attemptId);
        long answers = jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", Long.class, c.attemptId);
        long grades = jdbc.queryForObject("SELECT count(*) FROM grades WHERE attempt_id=?", Long.class, c.attemptId);
        long gradeItems = jdbc.queryForObject("SELECT count(*) FROM grade_items gi JOIN grades g ON g.id=gi.grade_id WHERE g.attempt_id=?", Long.class, c.attemptId);
        long idempotencyRecords = jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE attempt_id=?", Long.class, c.attemptId);
        return new Counts(attempts, attemptQuestions, answers, grades, gradeItems, idempotencyRecords);
    }

    private long foreignStudent(long schoolId) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long u = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('f" + s + "','f" + s + "@t.com','h','F')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + schoolId + ",'FS" + s + "')");
        return u;
    }

    private static String code(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private Chain setup(UUID attemptClientInstanceId) {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        long[] ids = new long[4];
        tx().executeWithoutResult(status -> {
            clock.setInstant(now);
            long u = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mt" + s + "','mt" + s + "@t.com','h','MT" + s + "')");
            long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
            long school = ins("INSERT INTO schools (code, name) VALUES ('MTS" + s + "','Sch')");
            long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
            long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
            long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + u + "," + school + ",'TC" + s + "')");
            long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + u + "," + school + ",'SC" + s + "')");
            long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
            long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + u + ")");
            long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + u + ")");
            long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
            long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + u + ")");
            long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
            long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
            jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
            long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES (" + school + "," + ver + "," + tp + ",'S" + s + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + u + ",'" + now.minusSeconds(3600) + "')");
            jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + u + ")");
            long attempt = attemptService.startAttempt(u, session, new StartAttemptRequest(attemptClientInstanceId)).attemptId();
            long aq = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attempt + " LIMIT 1", Long.class);
            ids[0] = u; ids[1] = school; ids[2] = attempt; ids[3] = aq;
        });
        return new Chain(ids[0], ids[1], ids[2], ids[3]);
    }

    private void cleanup(long attemptId) {
        try { tx().executeWithoutResult(st -> jdbc.update("DELETE FROM attempts WHERE id=?", attemptId)); } catch (Exception ignored) {}
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
