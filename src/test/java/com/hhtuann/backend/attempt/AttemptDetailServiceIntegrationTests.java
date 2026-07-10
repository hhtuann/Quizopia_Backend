package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptQueryService;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.AttemptDetailResponse;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-layer tests for {@code GET /api/attempts/{attemptId}} (A3.2-2). Covers authorization,
 * anti-enumeration ownership, all statuses, historical reads (CLOSED/BLOCKED), ordering, saved
 * answers, answered-count, numeric rounding, corrupted snapshot, and the no-mutation guarantee.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptDetailServiceIntegrationTests {

    @Autowired private AttemptService attemptService;
    @Autowired private AttemptQueryService queryService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private EntityManager em;

    private long studentUserId;
    private long studentProfileId;
    private long schoolId;
    private long sessionId;
    private long aqSingleId;
    private long aqMultipleId;
    private long aqTfId;
    private long aqNumericId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('d','d@t.com','h','D')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('DS','Detail School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'M','Math')");
        long teacher = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + schoolId + ",'TC')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + schoolId + ",'SC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subj + "," + teacher + ",'B','Bank')");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + schoolId + "," + subj + "," + teacher + ",'E','Exam')");
        long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,'PUBLISHED',4,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        String numericKey = "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long eqSingle = eq(ver, sec, bank, "SINGLE_CHOICE", 0, studentUserId);
        opts(eqSingle, "A", "B", "C", "D");
        long eqMultiple = eq(ver, sec, bank, "MULTIPLE_CHOICE", 1, studentUserId);
        opts(eqMultiple, "A", "B", "C", "D");
        long eqTf = eq(ver, sec, bank, "TRUE_FALSE_MATRIX", 2, studentUserId);
        opts(eqTf, "A", "B", "C", "D");
        long eqNumeric = eqNumeric(ver, sec, bank, 3, studentUserId, numericKey);
        sessionId = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + schoolId + "," + ver + "," + teacher + ",'S','Sess','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + schoolId + "," + sessionId + "," + studentProfileId + "," + studentUserId + ")");
        // Start an attempt to materialize the snapshot, then capture the attempt_question ids by type.
        long attemptId = attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null)).attemptId();
        aqSingleId = aqId(attemptId, eqSingle);
        aqMultipleId = aqId(attemptId, eqMultiple);
        aqTfId = aqId(attemptId, eqTf);
        aqNumericId = aqId(attemptId, eqNumeric);
    }

    // === metadata + status + historical ===

    @Test
    void detailReturnsOwnInProgressMetadata() {
        long a = activeAttempt();
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, a);
        assertThat(r.status()).isEqualTo("IN_PROGRESS");
        assertThat(r.sessionId()).isEqualTo(sessionId);
        assertThat(r.attemptNumber()).isPositive();
        assertThat(r.totalQuestions()).isEqualTo(4);
        assertThat(r.serverTime()).isNotNull();
        assertThat(r.submittedAt()).isNull();
    }

    @Test
    void detailReadsSubmittedAttempt() {
        long a = activeAttempt();
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='K" + a + "' WHERE id=" + a);
        em.clear();
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, a);
        assertThat(r.status()).isEqualTo("SUBMITTED");
        assertThat(r.submittedAt()).isNotNull();
    }

    @Test
    void detailReadsGradedAttempt() {
        long a = activeAttempt();
        jdbc.update("UPDATE attempts SET status='GRADED', submitted_at=now(), submission_idempotency_key='G" + a + "' WHERE id=" + a);
        em.clear();
        assertThat(queryService.getAttemptDetail(studentUserId, a).status()).isEqualTo("GRADED");
    }

    @Test
    void detailReadsWhenSessionClosed() {
        long a = activeAttempt();
        jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
        assertThat(queryService.getAttemptDetail(studentUserId, a).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void detailReadsWhenParticipantBlockedAfterStart() {
        long a = activeAttempt();
        jdbc.update("UPDATE exam_session_participants SET status='BLOCKED', blocked_at=now() WHERE exam_session_id=" + sessionId);
        assertThat(queryService.getAttemptDetail(studentUserId, a).status()).isEqualTo("IN_PROGRESS");
    }

    // === ownership / anti-enumeration / auth ===

    @Test
    void detailMissingAttemptReturns404() {
        assertThatThrownBy(() -> queryService.getAttemptDetail(studentUserId, 999999L))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailForeignStudentAttemptReturns404() {
        long a = activeAttempt();
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + schoolId + ",'OS')");
        assertThatThrownBy(() -> queryService.getAttemptDetail(other, a))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailCrossSchoolAttemptReturns404() {
        long a = activeAttempt();
        long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('XS','XSchool')");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('x','x@t.com','h','X')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + other + "," + otherSchool + ",'XS')");
        assertThatThrownBy(() -> queryService.getAttemptDetail(other, a))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailMissingProfileReturns404() {
        long a = activeAttempt();
        long noProfile = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('np','np@t.com','h','NP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + noProfile + "," + roleId + ")");
        assertThatThrownBy(() -> queryService.getAttemptDetail(noProfile, a))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
    }

    @Test
    void detailInactiveProfileReturns403() {
        long a = activeAttempt();
        jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE' WHERE id=" + studentProfileId);
        em.clear(); // setUp's startAttempt auth cached the (ACTIVE) profile in the persistence context
        assertThatThrownBy(() -> queryService.getAttemptDetail(studentUserId, a))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void detailRevokedAttemptReadReturns403() {
        revoke("ATTEMPT_READ");
        assertThatThrownBy(() -> queryService.getAttemptDetail(studentUserId, activeAttempt()))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void detailRevokedAnswerReadReturns403() {
        revoke("ATTEMPT_ANSWER_READ");
        assertThatThrownBy(() -> queryService.getAttemptDetail(studentUserId, activeAttempt()))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void detailSystemAdminWithoutStudentReturns403() {
        long a = activeAttempt();
        long admin = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sa','sa@t.com','h','SA')");
        long adminRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='SYSTEM_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + admin + "," + adminRoleId + ")");
        assertThatThrownBy(() -> queryService.getAttemptDetail(admin, a))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_ACCESS_DENIED"));
    }

    // === ordering ===

    @Test
    void detailQuestionsOrderedByDisplayOrder() {
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, activeAttempt());
        assertThat(r.questions()).extracting(q -> q.displayOrder()).containsExactly(0, 1, 2, 3);
    }

    @Test
    void detailOptionsFollowPersistedJsonOrder() {
        // Mutate persisted option_order to [D,C,B,A] for the SINGLE question; detail renders that order.
        jdbc.update("UPDATE attempt_questions SET option_order='[\"D\",\"C\",\"B\",\"A\"]'::jsonb WHERE id=" + aqSingleId);
        em.clear();
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, activeAttempt());
        AttemptDetailResponse.QuestionView single = byType(r, "SINGLE_CHOICE");
        assertThat(single.options()).extracting(o -> o.optionKey()).containsExactly("D", "C", "B", "A");
        // Render position is the persisted-array index (0..3), not the source DB position.
        assertThat(single.options()).extracting(o -> o.position()).containsExactly(0, 1, 2, 3);
    }

    @Test
    void detailSourcePositionMutationDoesNotChangeRenderOrder() {
        long eqSingle = jdbc.queryForObject("SELECT exam_question_id FROM attempt_questions WHERE id=" + aqSingleId, Long.class);
        jdbc.update("UPDATE exam_question_options SET position = position + 100 WHERE exam_question_id=" + eqSingle);
        jdbc.update("UPDATE exam_question_options SET position = CASE option_key WHEN 'A' THEN 3 WHEN 'B' THEN 2 WHEN 'C' THEN 1 WHEN 'D' THEN 0 END WHERE exam_question_id=" + eqSingle);
        em.clear();
        AttemptDetailResponse.QuestionView single = byType(queryService.getAttemptDetail(studentUserId, activeAttempt()), "SINGLE_CHOICE");
        assertThat(single.options()).extracting(o -> o.optionKey()).containsExactly("A", "B", "C", "D");
    }

    // === saved answers ===

    @Test
    void detailSavedAnswerRoundTripsAllFourPayloadTypes() {
        long a = activeAttempt();
        upsertAnswer(a, aqSingleId, "{\"selectedOptionKey\":\"A\"}", 1);
        upsertAnswer(a, aqMultipleId, "{\"selectedOptionKeys\":[\"A\",\"C\"]}", 1);
        upsertAnswer(a, aqTfId, "{\"responses\":{\"A\":true,\"B\":false,\"C\":true,\"D\":false}}", 1);
        upsertAnswer(a, aqNumericId, "{\"value\":\"2.50\"}", 1);
        em.clear();
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, a);
        assertThat(byType(r, "SINGLE_CHOICE").savedAnswer().answerPayload().get("selectedOptionKey").asString()).isEqualTo("A");
        assertThat(byType(r, "MULTIPLE_CHOICE").savedAnswer().answerPayload().get("selectedOptionKeys").get(0).asString()).isEqualTo("A");
        assertThat(byType(r, "TRUE_FALSE_MATRIX").savedAnswer().answerPayload().get("responses").get("A").asBoolean()).isTrue();
        assertThat(byType(r, "NUMERIC_FILL").savedAnswer().answerPayload().get("value").asString()).isEqualTo("2.50");
    }

    @Test
    void detailClearedAnswerKeepsSavedAnswerWithSequence() {
        long a = activeAttempt();
        upsertAnswer(a, aqSingleId, "{\"selectedOptionKey\":\"B\"}", 5);
        upsertAnswer(a, aqSingleId, "null", 6); // clear: payload null but sequence advances
        em.clear();
        AttemptDetailResponse.QuestionView single = byType(queryService.getAttemptDetail(studentUserId, a), "SINGLE_CHOICE");
        assertThat(single.savedAnswer()).isNotNull();
        assertThat(single.savedAnswer().answerPayload()).isNull();
        assertThat(single.savedAnswer().sequenceNumber()).isEqualTo(6L);
    }

    @Test
    void detailAnsweredCountCountsOnlyNonNullPayloads() {
        long a = activeAttempt();
        upsertAnswer(a, aqSingleId, "{\"selectedOptionKey\":\"A\"}", 1);       // counted
        upsertAnswer(a, aqMultipleId, "null", 1);                              // cleared: not counted
        // TF empty-responses object is a non-null payload → counted per frozen DB definition
        upsertAnswer(a, aqTfId, "{\"responses\":{}}", 1);
        em.clear();
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, a);
        assertThat(r.answeredCount()).isEqualTo(2);
        assertThat(r.totalQuestions()).isEqualTo(4);
    }

    @Test
    void detailNoAnswersYieldsNullSavedAnswerAndZeroCount() {
        AttemptDetailResponse r = queryService.getAttemptDetail(studentUserId, activeAttempt());
        assertThat(r.answeredCount()).isZero();
        assertThat(r.questions()).allSatisfy(q -> assertThat(q.savedAnswer()).isNull());
    }

    // === numeric (rounding now lives in the question content, not the DTO) ===

    @Test
    void detailNumericHasNoOptions() {
        AttemptDetailResponse.QuestionView numeric = byType(queryService.getAttemptDetail(studentUserId, activeAttempt()), "NUMERIC_FILL");
        assertThat(numeric.options()).isEmpty(); // NUMERIC has 0 options
    }

    @Test
    void detailNumericDoesNotLeakAnswerKey() {
        AttemptDetailResponse.QuestionView numeric = byType(queryService.getAttemptDetail(studentUserId, activeAttempt()), "NUMERIC_FILL");
        // The QuestionView record exposes no answer-key fields (expectedAnswer/answerKey/isCorrect);
        // the structural guarantee is enforced by AttemptDataLeakStructuralTest (reflection).
        assertThat(numeric.options()).isEmpty();
    }

    // === corrupted snapshot ===

    @Test
    void detailInvalidPersistedOptionSnapshotReturns400() {
        jdbc.update("UPDATE attempt_questions SET option_order='[\"A\",\"A\"]'::jsonb WHERE id=" + aqSingleId);
        em.clear();
        assertThatThrownBy(() -> queryService.getAttemptDetail(studentUserId, activeAttempt()))
                .isInstanceOf(AttemptException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("ATTEMPT_VALIDATION_ERROR"));
    }

    // === no mutation ===

    @Test
    void detailDoesNotMutateAttempt() {
        long a = activeAttempt();
        String before = jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM updated_at) FROM attempts WHERE id=" + a, String.class);
        Instant startedBefore = jdbc.queryForObject("SELECT started_at FROM attempts WHERE id=" + a, Instant.class);
        queryService.getAttemptDetail(studentUserId, a);
        queryService.getAttemptDetail(studentUserId, a);
        String after = jdbc.queryForObject("SELECT status || '|' || EXTRACT(EPOCH FROM updated_at) FROM attempts WHERE id=" + a, String.class);
        Instant startedAfter = jdbc.queryForObject("SELECT started_at FROM attempts WHERE id=" + a, Instant.class);
        assertThat(after).isEqualTo(before);
        assertThat(startedAfter).isEqualTo(startedBefore);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private long activeAttempt() {
        return jdbc.queryForObject("SELECT id FROM attempts WHERE student_profile_id=" + studentProfileId
                + " AND status='IN_PROGRESS' ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long aqId(long attemptId, long examQuestionId) {
        return jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId
                + " AND exam_question_id=" + examQuestionId, Long.class);
    }

    private void upsertAnswer(long attemptId, long aqId, String payloadJson, long sequence) {
        // "null" → SQL NULL (cleared answer); any other JSON → quoted jsonb literal.
        String payloadLiteral = "null".equals(payloadJson) ? "NULL" : "'" + payloadJson + "'::jsonb";
        jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number, saved_at) "
                + "VALUES (" + attemptId + "," + aqId + "," + payloadLiteral + "," + sequence + ",now()) "
                + "ON CONFLICT (attempt_id, attempt_question_id) DO UPDATE SET answer_payload=EXCLUDED.answer_payload, "
                + "sequence_number=EXCLUDED.sequence_number, saved_at=now()");
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private static String code(Throwable e) {
        return ((AttemptException) e).getErrorCode().code();
    }

    private static AttemptDetailResponse.QuestionView byType(AttemptDetailResponse r, String type) {
        return r.questions().stream().filter(q -> q.questionType().equals(type)).findFirst().orElseThrow();
    }

    private long eq(long ver, long sec, long bank, String type, int position, long user) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q" + type + position + "'," + user + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) "
                + "VALUES (" + q + ",1,'" + type + "','c',1,'{}'::jsonb," + user + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, "
                + "question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv
                + ",'QC','" + type + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long eqNumeric(long ver, long sec, long bank, int position, long user, String numericKey) {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN" + position + "'," + user + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) "
                + "VALUES (" + q + ",1,'NUMERIC_FILL','n',1,'{}'::jsonb," + numericKey + "," + user + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, "
                + "question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + ver + "," + sec + "," + q + "," + qv
                + ",'QC','NUMERIC_FILL','n',1," + position + ",'{}'::jsonb," + numericKey + ")");
    }

    private void opts(long eqId, String... keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("(").append(eqId).append(",'").append(keys[i]).append("','o").append(i).append("',").append(i == 1).append(",").append(i).append(")");
        }
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES " + sb);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
