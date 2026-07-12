package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.SubmitRequest;
import com.quizopia.backend.attempt.dto.SubmitResponse;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code POST /api/attempts/{attemptId}/submit} (A3.2-4 idempotent submit).
 * Covers authorization/ownership, key validation, state/deadline precedence, the first-submit
 * transition, the {@code IMMUTABLE_CACHED_RESPONSE} retry, different-key/cross-attempt conflicts,
 * and the no-mutation / no-grade invariants.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptSubmitServiceIntegrationTests {

    @Autowired private AttemptSubmitService submitService;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long studentUserId;
    private long attemptId;
    private long aqId;
    private static final String KEY = "submit-key-001";

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        studentUserId = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s','s@t.com','h','S')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long school = ins("INSERT INTO schools (code, name) VALUES ('SS','Submit School')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','Math')");
        long tp = ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + school + ",'TC')");
        long sp = ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC')");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','Bank')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','Exam')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + studentUserId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + studentUserId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        long session = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + "," + tp + ",'S','Sess','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " LIMIT 1", Long.class);
    }

    // === authorization / ownership ===

    @Test void validStudentSubmitAccepted() {
        SubmitResponse r = submit(studentUserId, KEY);
        assertThat(r.status()).isEqualTo("SUBMITTED");
        assertThat(r.attemptId()).isEqualTo(attemptId);
    }

    @Test void missingRoleRejected403() {
        revokeStudentRole();
        assertThatThrownBy(() -> submit(studentUserId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void revokedPermissionRejected403() {
        revoke("ATTEMPT_SUBMIT");
        assertThatThrownBy(() -> submit(studentUserId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void systemAdminWithoutStudentRejected403() {
        long admin = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sa','sa@t.com','h','SA')");
        long adminRole = jdbc.queryForObject("SELECT id FROM roles WHERE code='SYSTEM_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + admin + "," + adminRole + ")");
        assertThatThrownBy(() -> submit(admin, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void missingProfileRejected404() {
        long np = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t.com','h','NP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + np + "," + roleId + ")");
        assertThatThrownBy(() -> submit(np, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
    }

    @Test void inactiveProfileRejected403() {
        jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE'");
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void missingAttemptRejected404() {
        assertThatThrownBy(() -> submit(studentUserId, 999999L, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_NOT_FOUND"));
    }

    @Test void foreignAttemptRejected403() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'OS' FROM student_profiles WHERE user_id=" + studentUserId);
        assertThatThrownBy(() -> submit(other, attemptId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test void crossSchoolAttemptRejected403() {
        long xs = ins("INSERT INTO schools (code, name) VALUES ('XS','XS')");
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + xs + ",'XS')");
        assertThatThrownBy(() -> submit(other, attemptId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    // === key validation ===

    @Test void validUuidKeyAccepted() { assertThat(submit(studentUserId, "f81d4fae-7dec-11d0-a765-00a0c91e6bf6").status()).isEqualTo("SUBMITTED"); }
    @Test void validNonUuidKeyAccepted() { assertThat(submit(studentUserId, "A_B-C.01").status()).isEqualTo("SUBMITTED"); }
    @Test void nullRequestRejected400() { assertThatThrownBy(() -> submitService.submitAttempt(studentUserId, attemptId, null)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void nullKeyRejected400() { assertThatThrownBy(() -> submitService.submitAttempt(studentUserId, attemptId, new SubmitRequest(null))).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void blankKeyRejected400() { assertThatThrownBy(() -> submit(studentUserId, " ")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void emptyKeyRejected400() { assertThatThrownBy(() -> submit(studentUserId, "")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void leadingWhitespaceRejected400() { assertThatThrownBy(() -> submit(studentUserId, " " + KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void trailingWhitespaceRejected400() { assertThatThrownBy(() -> submit(studentUserId, KEY + " ")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void embeddedWhitespaceRejected400() { assertThatThrownBy(() -> submit(studentUserId, "key value")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }
    @Test void length100Accepted() { assertThat(submit(studentUserId, "k".repeat(100)).status()).isEqualTo("SUBMITTED"); }
    @Test void length101Rejected400() { assertThatThrownBy(() -> submit(studentUserId, "k".repeat(101))).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_VALIDATION_ERROR")); }

    @Test void keyRepresentationPreservedExactly() {
        submit(studentUserId, "A_B-C.01");
        String stored = jdbc.queryForObject("SELECT submission_idempotency_key FROM attempts WHERE id=?", String.class, attemptId);
        assertThat(stored).isEqualTo("A_B-C.01"); // exact, no normalization
    }

    // === state / deadline ===

    @Test void inProgressSubmitAccepted() { assertThat(submit(studentUserId, KEY).status()).isEqualTo("SUBMITTED"); }

    @Test void exactDeadlineAccepted() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline);
        assertThat(submit(studentUserId, KEY).status()).isEqualTo("SUBMITTED");
    }

    @Test void afterDeadlineRejected409() {
        Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId, Instant.class);
        clock.setInstant(deadline.plusSeconds(1));
        assertThatThrownBy(() -> submit(studentUserId, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_DEADLINE_EXCEEDED"));
    }

    @Test void submittedSameKeyReturnsCached() {
        SubmitResponse first = submit(studentUserId, KEY);
        em.clear();
        SubmitResponse retry = submit(studentUserId, KEY);
        assertThat(retry.attemptId()).isEqualTo(first.attemptId());
        assertThat(retry.submittedAt()).isEqualTo(first.submittedAt());
        assertThat(retry.serverTime()).isEqualTo(first.serverTime());
        assertThat(retry.attemptNumber()).isEqualTo(first.attemptNumber());
        assertThat(retry.status()).isEqualTo("SUBMITTED");
    }

    @Test void submittedDifferentKeyRejected409() {
        submit(studentUserId, KEY);
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, "other-key-002")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_ALREADY_SUBMITTED"));
    }

    @Test void gradedRejected409() {
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=now(), submission_idempotency_key='G' WHERE id=" + attemptId);
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, "G")).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_INVALID_STATE"));
    }

    @Test void closedSessionStillPermitsSubmit() {
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=(SELECT exam_session_id FROM attempts WHERE id=" + attemptId + ")");
        em.clear();
        assertThat(submit(studentUserId, KEY).status()).isEqualTo("SUBMITTED");
    }

    @Test void blockedParticipantStillPermitsSubmit() {
        jdbc.update("UPDATE exam_session_participants SET status='BLOCKED', blocked_at=now()");
        em.clear();
        assertThat(submit(studentUserId, KEY).status()).isEqualTo("SUBMITTED");
    }

    // === first-submit transition ===

    @Test void statusBecomesSubmitted() {
        submit(studentUserId, KEY);
        String status = jdbc.queryForObject("SELECT status FROM attempts WHERE id=?", String.class, attemptId);
        assertThat(status).isEqualTo("SUBMITTED");
    }

    @Test void submittedAtEqualsServerTimeAndAppNow() {
        Instant before = Instant.now(clock);
        SubmitResponse r = submit(studentUserId, KEY);
        Instant after = Instant.now(clock);
        assertThat(r.submittedAt()).isEqualTo(r.serverTime());
        // submittedAt == the single application `now` captured inside the service.
        assertThat(r.submittedAt()).isBetween(before, after);
    }

    @Test void submissionKeyPersistedExactly() {
        submit(studentUserId, "abc-XYZ_001");
        String stored = jdbc.queryForObject("SELECT submission_idempotency_key FROM attempts WHERE id=?", String.class, attemptId);
        assertThat(stored).isEqualTo("abc-XYZ_001");
    }

    @Test void firstSubmitLeavesAnswersAndQuestionsUnchanged() {
        jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number, saved_at) VALUES (" + attemptId + "," + aqId + ",('{\"selectedOptionKey\":\"A\"}')::jsonb,1,now())");
        long aqBefore = count("attempt_questions WHERE attempt_id=" + attemptId);
        long ansBefore = count("attempt_answers WHERE attempt_id=" + attemptId);
        submit(studentUserId, KEY);
        assertThat(count("attempt_questions WHERE attempt_id=" + attemptId)).isEqualTo(aqBefore);
        assertThat(count("attempt_answers WHERE attempt_id=" + attemptId)).isEqualTo(ansBefore);
        String payload = jdbc.queryForObject("SELECT answer_payload::text FROM attempt_answers WHERE attempt_id=" + attemptId, String.class);
        assertThat(payload).contains("selectedOptionKey"); // untouched
    }

    @Test void firstSubmitDoesNotChangeMetadataExceptStatus() {
        String before = metadata();
        submit(studentUserId, KEY);
        String after = jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM started_at) || '|' || EXTRACT(EPOCH FROM deadline_at) || '|' || "
                + "COALESCE(EXTRACT(EPOCH FROM last_saved_at)::text, 'null') || '|' || COALESCE(client_instance_id::text,'null') || '|' || attempt_number FROM attempts WHERE id=?", String.class, attemptId);
        // status changed (IN_PROGRESS→SUBMITTED) but the rest of the metadata is preserved.
        assertThat(after).startsWith("SUBMITTED|");
        assertThat(after.substring(after.indexOf('|'))).isEqualTo(before.substring(before.indexOf('|')));
    }

    @Test void exactlyOneGradeAndGradeItemCreated() {
        // Day 8: submit grades atomically — exactly one immutable Grade + one GradeItem per attempt question.
        submit(studentUserId, KEY);
        assertThat(count("grades WHERE attempt_id=" + attemptId)).isEqualTo(1);
        assertThat(count("grade_items WHERE attempt_id=" + attemptId)).isEqualTo(1);
        // The grade is AUTO_GRADED with the percentage set (no answers here ⇒ 0%).
        String row = jdbc.queryForObject(
                "SELECT status || '|' || COALESCE(percentage::text,'null') FROM grades WHERE attempt_id=" + attemptId, String.class);
        assertThat(row).startsWith("AUTO_GRADED|");
    }

    // === cache ===

    @Test void exactlyOneCacheRow() {
        submit(studentUserId, KEY);
        assertThat(count("idempotency_records WHERE attempt_id=" + attemptId)).isEqualTo(1);
    }

    @Test void cacheOperationStatusExpiresCorrect() {
        submit(studentUserId, KEY);
        String row = jdbc.queryForObject("SELECT operation || '|' || response_status || '|' || COALESCE(expires_at::text,'null') FROM idempotency_records WHERE attempt_id=?", String.class, attemptId);
        assertThat(row).isEqualTo("ATTEMPT_SUBMIT|200|null");
    }

    @Test void cachedJsonFieldsEqualFirstResponse() {
        SubmitResponse first = submit(studentUserId, KEY);
        String attemptIdJson = jdbc.queryForObject("SELECT response_body->>'attemptId' FROM idempotency_records WHERE attempt_id=?", String.class, attemptId);
        String statusJson = jdbc.queryForObject("SELECT response_body->>'status' FROM idempotency_records WHERE attempt_id=?", String.class, attemptId);
        String attemptNumberJson = jdbc.queryForObject("SELECT response_body->>'attemptNumber' FROM idempotency_records WHERE attempt_id=?", String.class, attemptId);
        assertThat(attemptIdJson).isEqualTo(first.attemptId().toString());
        assertThat(statusJson).isEqualTo("SUBMITTED");
        assertThat(attemptNumberJson).isEqualTo(first.attemptNumber().toString());
    }

    @Test void retryAfterAdvancingClockReturnsOriginalServerTime() {
        SubmitResponse first = submit(studentUserId, KEY);
        em.clear();
        clock.setInstant(first.serverTime().plusSeconds(3600));
        SubmitResponse retry = submit(studentUserId, KEY);
        assertThat(retry.serverTime()).isEqualTo(first.serverTime()); // not recomputed
    }

    @Test void retryDoesNotUpdateAttemptUpdatedAt() {
        submit(studentUserId, KEY);
        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM attempts WHERE id=?", Instant.class, attemptId);
        em.clear();
        clock.setInstant(Instant.now(clock).plusSeconds(120));
        submit(studentUserId, KEY); // retry
        Instant updatedAtAfter = jdbc.queryForObject("SELECT updated_at FROM attempts WHERE id=?", Instant.class, attemptId);
        assertThat(updatedAtAfter).isEqualTo(updatedAt); // retry is read-only on the attempt
    }

    @Test void retryDoesNotInsertCache() {
        submit(studentUserId, KEY);
        em.clear();
        submit(studentUserId, KEY);
        assertThat(count("idempotency_records WHERE attempt_id=" + attemptId)).isEqualTo(1);
    }

    @Test void retryDoesNotMutateAnswers() {
        jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number, saved_at) VALUES (" + attemptId + "," + aqId + ",('{\"selectedOptionKey\":\"A\"}')::jsonb,1,now())");
        submit(studentUserId, KEY);
        String before = jdbc.queryForObject("SELECT answer_payload::text FROM attempt_answers WHERE attempt_id=" + attemptId, String.class);
        em.clear();
        submit(studentUserId, KEY);
        String after = jdbc.queryForObject("SELECT answer_payload::text FROM attempt_answers WHERE attempt_id=" + attemptId, String.class);
        assertThat(after).isEqualTo(before);
    }

    @Test void malformedCachedBodyReturnsGeneric500() {
        submit(studentUserId, KEY);
        // A valid JSON object (passes chk_idempotency_body) that is not a SubmitResponse → validation fails → 500.
        jdbc.update("UPDATE idempotency_records SET response_body='{\"foo\":\"bar\"}'::jsonb WHERE attempt_id=?", attemptId);
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, KEY))
                .isNotInstanceOf(AttemptException.class); // generic IllegalStateException → 500
    }

    @Test void missingCacheForSubmittedAttemptReturnsGeneric500() {
        submit(studentUserId, KEY);
        jdbc.update("DELETE FROM idempotency_records WHERE attempt_id=?", attemptId);
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, KEY)).isNotInstanceOf(AttemptException.class);
    }

    @Test void cachedAttemptMismatchReturnsGeneric500() {
        submit(studentUserId, KEY);
        // Corrupt the cached attemptId so it no longer matches the current attempt.
        jdbc.update("UPDATE idempotency_records SET response_body=jsonb_set(response_body,'{attemptId}','999999') WHERE attempt_id=?", attemptId);
        em.clear();
        assertThatThrownBy(() -> submit(studentUserId, KEY)).isNotInstanceOf(AttemptException.class);
    }

    // === conflict ===

    @Test void sameUserReusesKeyOnAnotherAttemptRejected409() {
        submit(studentUserId, KEY); // attempt 1
        long attempt2 = startSecondAttempt();
        assertThatThrownBy(() -> submit(studentUserId, attempt2, KEY)).satisfies(c -> assertThat(code(c)).isEqualTo("ATTEMPT_IDEMPOTENCY_CONFLICT"));
    }

    @Test void anotherUserMayUseSameTextualKey() {
        long other = createSecondStudentSameSchool();
        long attempt2 = startAttemptFor(other);
        submit(studentUserId, attemptId, KEY); // user 1, key
        assertThat(submit(other, attempt2, KEY).status()).isEqualTo("SUBMITTED"); // user 2 same key OK
    }

    @Test void conflictRollbackLeavesTargetInProgress() {
        submit(studentUserId, KEY);
        long attempt2 = startSecondAttempt();
        assertThatThrownBy(() -> submit(studentUserId, attempt2, KEY)).isInstanceOf(AttemptException.class);
        String status2 = jdbc.queryForObject("SELECT status FROM attempts WHERE id=?", String.class, attempt2);
        Instant submitted2 = jdbc.queryForObject("SELECT submitted_at FROM attempts WHERE id=?", Instant.class, attempt2);
        String key2 = jdbc.queryForObject("SELECT submission_idempotency_key FROM attempts WHERE id=?", String.class, attempt2);
        assertThat(status2).isEqualTo("IN_PROGRESS");
        assertThat(submitted2).isNull();
        assertThat(key2).isNull();
    }

    @Test void constraintNameTranslationExact() {
        // Direct path: a record for another attempt exists → precheck → ATTEMPT_IDEMPOTENCY_CONFLICT (no flush needed).
        submit(studentUserId, KEY);
        long attempt2 = startSecondAttempt();
        assertThatThrownBy(() -> submit(studentUserId, attempt2, KEY))
                .satisfies(c -> { assertThat(c).isInstanceOf(AttemptException.class); assertThat(code(c)).isEqualTo("ATTEMPT_IDEMPOTENCY_CONFLICT"); });
    }

    // === helpers ===

    private SubmitResponse submit(long userId, String key) { return submit(userId, attemptId, key); }

    private SubmitResponse submit(long userId, long attemptId, String key) {
        return submitService.submitAttempt(userId, attemptId, new SubmitRequest(key));
    }

    private long startSecondAttempt() {
        // The first attempt is now SUBMITTED (one-active is satisfied); a second attempt can start.
        return startAttemptFor(studentUserId);
    }

    private long startAttemptFor(long userId) {
        long session = jdbc.queryForObject("SELECT exam_session_id FROM attempts WHERE id=" + attemptId, Long.class);
        return attemptService.startAttempt(userId, session, new StartAttemptRequest(null)).attemptId();
    }

    private long createSecondStudentSameSchool() {
        long other = ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s2','s2@t.com','h','S2')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'SC2' FROM student_profiles WHERE user_id=" + studentUserId);
        long session = jdbc.queryForObject("SELECT exam_session_id FROM attempts WHERE id=" + attemptId, Long.class);
        // add as participant so they can start an attempt in this session
        long school = jdbc.queryForObject("SELECT school_id FROM attempts WHERE id=" + attemptId, Long.class);
        long sp = jdbc.queryForObject("SELECT id FROM student_profiles WHERE user_id=" + other, Long.class);
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + studentUserId + ")");
        return other;
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private void revokeStudentRole() {
        jdbc.update("DELETE FROM user_roles WHERE user_id=" + studentUserId);
    }

    private String metadata() {
        return jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM started_at) || '|' || EXTRACT(EPOCH FROM deadline_at) || '|' || "
                + "COALESCE(EXTRACT(EPOCH FROM last_saved_at)::text, 'null') || '|' || COALESCE(client_instance_id::text,'null') || '|' || attempt_number FROM attempts WHERE id=" + attemptId, String.class);
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM " + where, Long.class);
    }

    private static String code(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private long ins(String sql) { return jdbc.queryForObject(sql + " RETURNING id", Long.class); }
}
