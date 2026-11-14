package com.quizopia.backend.schema;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests verifying the attempt/grading schema constraints (CHECK,
 * FK, composite ownership FK, unique, partial unique, submission invariant,
 * idempotency invariants) on a real PostgreSQL 17 instance via Testcontainers,
 * against the CURRENT head schema (V1–V25, user-centric contract: attempts are
 * keyed by user_id; school/profile provenance columns are gone).
 *
 * <p>Direct {@link JdbcTemplate} SQL, id-via-RETURNING,
 * {@code DataIntegrityViolationException} assertions for rejected rows, and
 * {@code pg_catalog} checks for named constraints/indexes. Staged-Flyway
 * proof lives in {@code V9AttemptSchemaStagedFlywayTest} on its own container.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V9AttemptSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    // Monotonic counter for unique creator-scoped exam/session codes within a single test.
    private long awSeq = 0;

    // Fixture ids (set up per test; rolled back after).
    private long userId;
    private long learnerId;
    private long gradeLevelId;
    private long subjectId;
    private long bankId;
    private long questionId;
    private long questionVersionId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u9','u9@t.com','h','U9')");
        learnerId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u9l','u9l@t.com','h','U9L')");
        gradeLevelId = insert("INSERT INTO grade_levels (code, name) VALUES ('GL12','G12')");
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) "
                + "VALUES (" + gradeLevelId + ",'MATH','Math')");
        bankId = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + userId + "," + subjectId + ",'B1','Bank')");
        questionId = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'Q1'," + userId + ")");
        questionVersionId = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                + "content, difficulty, default_points, metadata, created_by) "
                + "VALUES (" + questionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + userId + ")");
    }

    // ============================================================
    // GROUP 1 — Migration & shape
    // ============================================================

    @Test
    void flywayAppliedV1ThroughV25() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN "
                        + "('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17',"
                        + "'18','19','20','21','22','23','24','25')", Integer.class);
        assertThat(n).isEqualTo(25);
        String current = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(current).isEqualTo("36");
    }

    @Test
    void allAttemptTablesExistAndNoOutOfScope() {
        for (String t : new String[]{"attempts", "attempt_questions", "attempt_answers",
                "grades", "grade_items", "idempotency_records"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // No out-of-scope tables.
        for (String forbidden : new String[]{"attempt_events", "attempt_sessions", "proctor_assignments",
                "manual_overrides", "audit_log", "outbox_events", "heartbeat",
                "attempt_question_versions"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + forbidden + "'", Integer.class))
                    .as("forbidden table %s must not exist", forbidden).isZero();
        }
    }

    @Test
    void coreTablesStillPresent() {
        for (String t : new String[]{"platform_metadata", "users", "roles", "permissions", "user_roles",
                "role_permissions", "refresh_sessions", "grade_levels", "subjects",
                "question_banks", "questions", "question_versions",
                "question_options", "exam_purposes", "exams", "exam_sections",
                "exam_questions", "exam_question_options", "exam_sessions", "exam_session_participants"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // The school/workspace-era tables are gone after V25.
        for (String t : new String[]{"schools", "teacher_profiles", "student_profiles",
                "organizations", "workspaces", "workspace_memberships", "exam_versions",
                "legacy_exam_content_map"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("legacy table %s", t).isZero();
        }
    }

    @Test
    void noOutOfContractColumns() {
        // attempt_answers must NOT carry the docs/database.md legacy columns.
        assertThat(columnExists("attempt_answers", "answer_text")).isFalse();
        assertThat(columnExists("attempt_answers", "is_final")).isFalse();
        // idempotency_records must NOT carry request_hash (A1.2 dropped it).
        assertThat(columnExists("idempotency_records", "request_hash")).isFalse();
        // grade_items must NOT use exam_question_id (attempt-scoped via attempt_question_id).
        assertThat(columnExists("grade_items", "exam_question_id")).isFalse();
        // attempt_questions must carry the denormalized snapshot columns.
        assertThat(columnExists("attempt_questions", "question_type")).isTrue();
        assertThat(columnExists("attempt_questions", "default_points")).isTrue();
        assertThat(columnExists("attempt_questions", "option_order")).isTrue();
        // V25: attempts carry no school/profile provenance — user_id is the identity.
        assertThat(columnExists("attempts", "school_id")).isFalse();
        assertThat(columnExists("attempts", "student_profile_id")).isFalse();
    }

    @Test
    void noExpiresAtIndexOnIdempotencyRecords() {
        // A1.2 D26: expires_at is forced NULL in MVP and has no (dormant) index.
        Integer idxCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename='idempotency_records' "
                        + "AND indexdef ILIKE '%expires_at%'", Integer.class);
        assertThat(idxCount).isZero();
    }

    // ============================================================
    // GROUP 2 — Named constraints / indexes exist (catalog)
    // ============================================================

    @Test
    void namedConstraintsExist() {
        for (String name : new String[]{
                // fk_attempts_student_school was DROPPED by V15 (PUBLIC sessions are
                // platform-wide); uk_attempts_session_student_number was REPLACED by
                // uk_attempts_session_user_number in V16 (user-based attempts).
                "fk_attempts_session", "fk_attempts_user",
                "chk_attempts_status", "chk_attempts_number", "chk_attempts_deadline",
                "chk_attempts_submission_invariant", "uk_attempts_session_user_number",
                "fk_attempt_questions_attempt", "fk_attempt_questions_exam",
                "chk_attempt_questions_type", "uk_attempt_questions_attempt_id",
                "fk_attempt_answers_attempt", "fk_attempt_answers_question_attempt",
                "chk_attempt_answers_seq", "chk_attempt_answers_payload",
                "uk_attempt_answers_attempt_question",
                "fk_grades_attempt", "fk_grades_graded_by",
                "chk_grades_auto", "chk_grades_final", "chk_grades_released_invariant",
                "uk_grades_attempt", "uk_grades_id_attempt",
                "fk_grade_items_grade_attempt", "fk_grade_items_attempt_question",
                "chk_grade_items_awarded", "uk_grade_items_grade_attempt_question",
                "fk_idempotency_user", "fk_idempotency_attempt",
                "chk_idempotency_operation", "chk_idempotency_status", "chk_idempotency_body",
                "chk_idempotency_no_expiry",
                "uk_idempotency_user_operation_key", "uk_idempotency_attempt_operation"}) {
            assertThat(constraintExists(name))
                    .as("constraint %s must exist", name).isTrue();
        }
    }

    @Test
    void namedIndexesExist() {
        for (String name : new String[]{
                // V16 moved uniqueness from student_profile_id to user_id; V25 dropped
                // the legacy student indexes with the student_profile_id column.
                "uk_attempts_one_active_per_session_user", "uk_attempts_user_submit_key",
                "idx_attempts_session_status", "idx_attempts_user",
                "idx_attempt_questions_exam"}) {
            assertThat(indexExists(name))
                    .as("index %s must exist", name).isTrue();
        }
        // Legacy student-profile indexes are gone with the column.
        assertThat(indexExists("idx_attempts_student")).as("legacy index must be gone").isFalse();
        assertThat(indexExists("idx_attempts_session_student")).as("legacy index must be gone").isFalse();
    }

    // ============================================================
    // GROUP 3 — attempts: FKs + invariants (user-based since V16/V25)
    // ============================================================

    @Test
    void attemptRejectsMissingSession() {
        // V24: fk_attempts_session (plain session FK) — a row pointing at a
        // missing session is rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, started_at, deadline_at) VALUES (99999999," + learnerId
                        + ",1,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsMissingUser() {
        long exam = newExam("EA");
        long session = newOpenSession(exam, "SA");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, started_at, deadline_at) VALUES (" + session + ",99999999,"
                        + "1,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAcceptsAnyUser() {
        // PUBLIC sessions are platform-wide: any user may own an attempt
        // (no profile or school provenance exists anymore).
        long exam = newExam("EA2");
        long session = newOpenSession(exam, "SA2");
        long id = insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + learnerId
                + ",1,now(),now()+interval '1 hour')");
        assertThat(id).isPositive();
    }

    @Test
    void attemptRejectsInvalidStatus() {
        long exam = newExam("ST");
        long session = newOpenSession(exam, "ST");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at) VALUES ("
                        + session + "," + learnerId + ",1,'CREATED',now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsAttemptNumberZero() {
        long exam = newExam("NZ");
        long session = newOpenSession(exam, "NZ");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + learnerId
                        + ",0,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsDeadlineBeforeStarted() {
        long exam = newExam("DL");
        long session = newOpenSession(exam, "DL");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + learnerId
                        + ",1,now()+interval '2 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptNumberDuplicateRejected() {
        long exam = newExam("ND");
        long session = newOpenSession(exam, "ND");
        newAttempt(session, learnerId, 1);
        assertThatThrownBy(() -> newAttempt(session, learnerId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneInProgressPerSessionUser() {
        long exam = newExam("OA");
        long session = newOpenSession(exam, "OA");
        newAttempt(session, learnerId, 1);
        // Second IN_PROGRESS for the same (session, user) -> partial unique violation.
        assertThatThrownBy(() -> newAttempt(session, learnerId, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inProgressThenSubmittedForSameSessionUserAccepted() {
        long exam = newExam("IS");
        long session = newOpenSession(exam, "IS");
        long a1 = newAttempt(session, learnerId, 1);
        // Submit attempt 1, then a fresh IN_PROGRESS attempt 2 is allowed (one-active is IN_PROGRESS-scoped).
        submit(a1, "K1");
        long a2 = newAttempt(session, learnerId, 2);
        assertThat(a2).isPositive();
    }

    @Test
    void twoInProgressDifferentUsersAccepted() {
        long exam = newExam("TS");
        long session = newOpenSession(exam, "TS");
        newAttempt(session, learnerId, 1);
        long a2 = newAttempt(session, userId, 1);
        assertThat(a2).isPositive();
    }

    // ============================================================
    // GROUP 3b — user-based attempt identity (V16; sole identity after V25)
    // ============================================================

    @Test
    void userBasedAttemptAccepted() {
        // user_id is the sole attempt identity: any user (no profile concept)
        // may own an attempt row.
        long exam = newExam("PU");
        long session = newOpenSession(exam, "PU");
        long pub = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('pub','pub@t.com','h','PUB')");
        long id = insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + pub
                + ",1,now(),now()+interval '1 hour')");
        assertThat(id).isPositive();
        Integer storedUser = jdbc.queryForObject(
                "SELECT user_id FROM attempts WHERE id=" + id, Integer.class);
        assertThat(storedUser).isEqualTo((int) pub);
    }

    @Test
    void duplicateSessionUserNumberRejected() {
        // V16 uniqueness is (exam_session_id, user_id, attempt_number).
        long exam = newExam("UU");
        long session = newOpenSession(exam, "UU");
        long pub = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('puu','puu@t.com','h','PUU')");
        insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + pub
                + ",1,now(),now()+interval '1 hour')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, started_at, deadline_at) VALUES (" + session + "," + pub
                        + ",1,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userIdIsNotNullableSoleIdentity() {
        // user_id is the primary identity (NOT NULL since V12); the profile
        // provenance column is GONE since V25.
        assertThat(columnNotNull("attempts", "user_id")).isTrue();
        assertThat(columnExists("attempts", "student_profile_id")).isFalse();
    }

    @Test
    void teacherRoleGainsLearnerAttemptPermissions() {
        // Dual creator+learner capability: TEACHER gains the three learner-scoped
        // attempt permissions (no admin powers).
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.code = 'TEACHER' "
                        + "AND p.code IN ('ATTEMPT_START','ATTEMPT_ANSWER_SAVE','ATTEMPT_SUBMIT')",
                Integer.class);
        assertThat(n).isEqualTo(3);
    }

    private boolean columnNotNull(String table, String column) {
        return "NO".equals(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name='" + table
                        + "' AND column_name='" + column + "'", String.class));
    }

    // --- Submission invariant (D25) ---

    @Test
    void submissionInvariantInProgressWithSubmittedAtRejected() {
        long session = newOpenSession(newExam("S1"), "S1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at, submitted_at) VALUES ("
                        + session + "," + learnerId + ",1,'IN_PROGRESS',now(),"
                        + "now()+interval '1 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantInProgressWithKeyRejected() {
        long session = newOpenSession(newExam("S2"), "S2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at, submission_idempotency_key) "
                        + "VALUES (" + session + "," + learnerId + ",1,'IN_PROGRESS',"
                        + "now(),now()+interval '1 hour','K')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantSubmittedWithNullSubmittedAtRejected() {
        long session = newOpenSession(newExam("S3"), "S3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at, submission_idempotency_key) "
                        + "VALUES (" + session + "," + learnerId + ",1,'SUBMITTED',"
                        + "now(),now()+interval '1 hour','K')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantSubmittedWithNullKeyRejected() {
        long session = newOpenSession(newExam("S4"), "S4");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at, submitted_at) VALUES ("
                        + session + "," + learnerId + ",1,'SUBMITTED',now(),"
                        + "now()+interval '1 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantGradedRequiresBothTimestampsAndKey() {
        long session = newOpenSession(newExam("S5"), "S5");
        // GRADED with both set is accepted.
        long ok = insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES ("
                + session + "," + learnerId + ",1,'GRADED',now(),"
                + "now()+interval '1 hour',now(),'KG')");
        assertThat(ok).isPositive();
        // GRADED with null key rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (exam_session_id, user_id, "
                        + "attempt_number, status, started_at, deadline_at, submitted_at, "
                        + "submission_idempotency_key) VALUES (" + session + "," + learnerId
                        + ",2,'GRADED',now(),now()+interval '1 hour',now(),NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullableSubmitKeysDoNotConflict() {
        // Two distinct sessions, same user, both IN_PROGRESS with NULL key -> no conflict (partial index).
        long exam = newExam("NK");
        long s1 = newOpenSession(exam, "N1");
        long s2 = newOpenSession(exam, "N2");
        long a1 = newAttempt(s1, learnerId, 1);
        long a2 = newAttempt(s2, learnerId, 1);
        assertThat(a1).isPositive();
        assertThat(a2).isPositive();
    }

    @Test
    void nonNullSubmitKeyDuplicatePerUserRejected() {
        long exam = newExam("DK");
        long s1 = newOpenSession(exam, "D1");
        long s2 = newOpenSession(exam, "D2");
        long a1 = newAttempt(s1, learnerId, 1);
        submit(a1, "DUP-KEY");
        long a2 = newAttempt(s2, learnerId, 1);
        // Reusing the same submit key for a different attempt of the same user -> partial unique violation.
        assertThatThrownBy(() -> submit(a2, "DUP-KEY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 4 — attempt_questions
    // ============================================================

    @Test
    void attemptQuestionRejectsInvalidType() {
        long exam = newExam("QT");
        long session = newOpenSession(exam, "QT");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'ESSAY',1,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsNonPositivePoints() {
        long exam = newExam("QP");
        long session = newOpenSession(exam, "QP");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',0,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsNegativeDisplayOrder() {
        long exam = newExam("QO");
        long session = newOpenSession(exam, "QO");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',1,-1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsOptionOrderNonArray() {
        long exam = newExam("QR");
        long session = newOpenSession(exam, "QR");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order, option_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',1,0,'{\"a\":1}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionDuplicateExamQuestionRejected() {
        long exam = newExam("QD");
        long session = newOpenSession(exam, "QD");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        newAttemptQuestion(attempt, eq, 0);
        assertThatThrownBy(() -> newAttemptQuestion(attempt, eq, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionDuplicateDisplayOrderRejected() {
        long exam = newExam("QX");
        long session = newOpenSession(exam, "QX");
        long attempt = newAttempt(session, learnerId, 1);
        long eqA = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        // A second DISTINCT source question/version (else uk_exam_questions_exam_source rejects first).
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'QXb',"
                + userId + ")");
        long q2v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c2',1,'{}'::jsonb,"
                + userId + ")");
        long section2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S2',1)");
        long eqB = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + exam + "," + section2 + "," + q2 + "," + q2v + ",'QC','SINGLE_CHOICE','c2',1,1,'{}'::jsonb)");
        newAttemptQuestion(attempt, eqA, 0);
        // Same display_order for a different exam_question in the same attempt -> UQ violation.
        assertThatThrownBy(() -> newAttemptQuestion(attempt, eqB, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 5 — attempt_answers (composite ownership D17)
    // ============================================================

    @Test
    void attemptAnswerRejectsCrossAttemptOwnership() {
        long exam = newExam("AC");
        long session = newOpenSession(exam, "AC");
        long attemptA = newAttempt(session, learnerId, 1);
        // A second attempt is impossible for the same (session,user) while A is IN_PROGRESS,
        // so use a different user to create attemptB and its attempt_question.
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ac2','ac2@t.com','h','AC2')");
        long attemptB = newAttempt(session, u2, 1);
        long eqB = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        long aqB = newAttemptQuestion(attemptB, eqB, 0);
        // answer claims attempt_id=attemptA but attempt_question_id belongs to attemptB -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + attemptA + "," + aqB
                        + ",'{\"selectedOptionKey\":\"A\"}'::jsonb,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAnswerRejectsSequenceZero() {
        long answer = newAttemptWithQuestion();
        long aq = attemptQuestionOf(answer);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + answer + "," + aq + ",NULL,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAnswerRejectsSequenceNegative() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + attempt + "," + aq + ",NULL,-3)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAnswerRejectsScalarPayload() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + attempt + "," + aq
                        + ",'\"x\"'::jsonb,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAnswerRejectsArrayPayload() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + attempt + "," + aq + ",'[1,2]'::jsonb,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptAnswerAcceptsNullAndObjectPayload() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        long r1 = insert("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                + "VALUES (" + attempt + "," + aq + ",NULL,1)");
        assertThat(r1).isPositive();
        // Same (attempt, attempt_question) is unique; clear the row first so the object insert is a fresh row.
        jdbc.update("DELETE FROM attempt_answers WHERE id=" + r1);
        long r2 = insert("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                + "VALUES (" + attempt + "," + aq + ",'{\"selectedOptionKey\":\"A\"}'::jsonb,2)");
        assertThat(r2).isPositive();
    }

    @Test
    void attemptAnswerDuplicateAttemptQuestionRejected() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        insert("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                + "VALUES (" + attempt + "," + aq + ",NULL,1)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, "
                        + "answer_payload, sequence_number) VALUES (" + attempt + "," + aq + ",NULL,2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 6 — grades
    // ============================================================

    @Test
    void gradeRejectsAutomaticAboveMax() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, graded_at) VALUES (" + attempt + ",11,5,10,now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeRejectsFinalAboveMax() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, graded_at) VALUES (" + attempt + ",5,11,10,now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeRejectsNegativeScore() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, graded_at) VALUES (" + attempt + ",-1,5,10,now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeRejectsMaxZero() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, graded_at) VALUES (" + attempt + ",0,0,0,now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeRejectsInvalidStatus() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, status, graded_at) VALUES (" + attempt + ",5,5,10,'PENDING',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeAutoGradedHappyPathsAccepted() {
        // AUTO_GRADED default (released_at null) and explicit RELEASED with released_at are both accepted.
        long a1 = newAttemptWithQuestion();
        assertThat(newGrade(a1)).isPositive();
        long a2 = newAttemptWithQuestion();
        long released = insert("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, status, "
                + "graded_at, released_at) VALUES (" + a2 + ",5,5,10,'RELEASED',now(),now())");
        assertThat(released).isPositive();
    }

    @Test
    void gradeAutoGradedWithReleasedAtRejected() {
        long a1 = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, status, graded_at, released_at) VALUES (" + a1 + ",5,5,10,'AUTO_GRADED',now(),now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeReleasedWithNullReleasedAtRejected() {
        long a2 = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grades (attempt_id, automatic_score, final_score, "
                        + "max_score, status, graded_at) VALUES (" + a2 + ",5,5,10,'RELEASED',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradedByDeleteIsRestricted() {
        long attempt = newAttemptWithQuestion();
        long grader = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('gr','gr@t.com','h','GR')");
        insert("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, graded_at, graded_by) "
                + "VALUES (" + attempt + ",5,5,10,now()," + grader + ")");
        // The grader user is referenced only by grades.graded_by (RESTRICT) -> delete must be rejected.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id=" + grader))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeEnforcesOnePerAttempt() {
        long attempt = newAttemptWithQuestion();
        newGrade(attempt);
        assertThatThrownBy(() -> newGrade(attempt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 7 — grade_items (ownership D18)
    // ============================================================

    @Test
    void gradeItemRejectsGradeAttemptMismatch() {
        long attemptA = newAttemptWithQuestion();
        long attemptB = newAttemptWithQuestion();
        long gradeB = newGrade(attemptB);
        long aqB = attemptQuestionOf(attemptB);
        // grade_id from B but attempt_id from A -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, "
                        + "awarded_points, max_points, is_correct) VALUES (" + gradeB + "," + attemptA + "," + aqB
                        + ",1,1,true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeItemRejectsAttemptQuestionMismatch() {
        long attemptA = newAttemptWithQuestion();
        long attemptB = newAttemptWithQuestion();
        long gradeA = newGrade(attemptA);
        long aqB = attemptQuestionOf(attemptB);
        // attempt_id from A but attempt_question_id from B -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, "
                        + "awarded_points, max_points, is_correct) VALUES (" + gradeA + "," + attemptA + "," + aqB
                        + ",1,1,true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeItemRejectsAwardedAboveMax() {
        long attempt = newAttemptWithQuestion();
        long grade = newGrade(attempt);
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, "
                        + "awarded_points, max_points, is_correct) VALUES (" + grade + "," + attempt + "," + aq
                        + ",2,1,true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeItemRejectsNullIsCorrect() {
        long attempt = newAttemptWithQuestion();
        long grade = newGrade(attempt);
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, "
                        + "awarded_points, max_points, is_correct) VALUES (" + grade + "," + attempt + "," + aq
                        + ",1,1,NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeItemRejectsNonObjectDetails() {
        long attempt = newAttemptWithQuestion();
        long grade = newGrade(attempt);
        long aq = attemptQuestionOf(attempt);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, "
                        + "awarded_points, max_points, is_correct, grading_details) VALUES (" + grade + "," + attempt
                        + "," + aq + ",1,1,true,'[1]'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gradeItemAcceptedHappyPath() {
        long attempt = newAttemptWithQuestion();
        long grade = newGrade(attempt);
        long aq = attemptQuestionOf(attempt);
        long gi = insert("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, awarded_points, "
                + "max_points, is_correct) VALUES (" + grade + "," + attempt + "," + aq + ",1,1,true)");
        assertThat(gi).isPositive();
    }

    // ============================================================
    // GROUP 8 — idempotency_records
    // ============================================================

    @Test
    void twoIdempotencyRecordsForSameAttemptRejected() {
        long attempt = newAttemptWithQuestion();
        newIdempotency(attempt, "K1");
        // Same attempt, different key -> UQ (attempt_id, operation) violation.
        assertThatThrownBy(() -> newIdempotency(attempt, "K2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reuseUserOperationKeyAcrossAttemptsRejected() {
        long attemptA = newAttemptWithQuestion();
        long attemptB = newAttemptWithQuestion();
        newIdempotency(attemptA, "SHARED");
        // Same (user, operation, key) on a different attempt -> UQ violation.
        assertThatThrownBy(() -> newIdempotency(attemptB, "SHARED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyExpiresAtNonNullRejected() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, "
                        + "idempotency_key, response_status, response_body, expires_at) VALUES (" + userId + "," + attempt
                        + ",'ATTEMPT_SUBMIT','E',200,'{\"a\":1}'::jsonb,now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyResponseBodyNonObjectRejected() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, "
                        + "idempotency_key, response_status, response_body) VALUES (" + userId + "," + attempt
                        + ",'ATTEMPT_SUBMIT','A',200,'[1]'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyResponseStatus201Rejected() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, "
                        + "idempotency_key, response_status, response_body) VALUES (" + userId + "," + attempt
                        + ",'ATTEMPT_SUBMIT','C',201,'{\"a\":1}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyResponseStatus500Rejected() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, "
                        + "idempotency_key, response_status, response_body) VALUES (" + userId + "," + attempt
                        + ",'ATTEMPT_SUBMIT','D',500,'{\"a\":1}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyRejectsWrongOperation() {
        long attempt = newAttemptWithQuestion();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records (user_id, attempt_id, operation, "
                        + "idempotency_key, response_status, response_body) VALUES (" + userId + "," + attempt
                        + ",'AUTOSAVE','X',200,'{\"a\":1}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyHappyPathAccepted() {
        long attempt = newAttemptWithQuestion();
        long r = newIdempotency(attempt, "OK");
        assertThat(r).isPositive();
    }

    // ============================================================
    // GROUP 9 — FK delete rules
    // ============================================================

    @Test
    void deleteAttemptCascadesToAttemptQuestionsAndAnswers() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        insert("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                + "VALUES (" + attempt + "," + aq + ",'{\"selectedOptionKey\":\"A\"}'::jsonb,1)");
        jdbc.update("DELETE FROM attempts WHERE id=" + attempt);
        assertThat(count("attempts", attempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_questions WHERE attempt_id=" + attempt,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_id=" + attempt,
                Integer.class)).isZero();
    }

    @Test
    void deleteAttemptQuestionCascadesAnswers() {
        long attempt = newAttemptWithQuestion();
        long aq = attemptQuestionOf(attempt);
        insert("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number) "
                + "VALUES (" + attempt + "," + aq + ",'{\"selectedOptionKey\":\"A\"}'::jsonb,1)");
        jdbc.update("DELETE FROM attempt_questions WHERE id=" + aq);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt_answers WHERE attempt_question_id=" + aq,
                Integer.class)).isZero();
    }

    @Test
    void deleteGradeCascadesItems() {
        long attempt = newAttemptWithQuestion();
        long grade = newGrade(attempt);
        long aq = attemptQuestionOf(attempt);
        insert("INSERT INTO grade_items (grade_id, attempt_id, attempt_question_id, awarded_points, max_points, "
                + "is_correct) VALUES (" + grade + "," + attempt + "," + aq + ",1,1,true)");
        jdbc.update("DELETE FROM grades WHERE id=" + grade);
        assertThat(count("grades", grade)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM grade_items WHERE grade_id=" + grade, Integer.class)).isZero();
    }

    @Test
    void deleteAttemptRestrictedByGrade() {
        long attempt = newAttemptWithQuestion();
        newGrade(attempt);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM attempts WHERE id=" + attempt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteAttemptRestrictedByIdempotency() {
        long attempt = newAttemptWithQuestion();
        newIdempotency(attempt, "K");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM attempts WHERE id=" + attempt))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteSessionRestrictedByAttempt() {
        long exam = newExam("DR");
        long session = newOpenSession(exam, "DR");
        newAttempt(session, learnerId, 1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_sessions WHERE id=" + session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteUserRestrictedByAttempt() {
        // fk_attempts_user (V12) RESTRICTs user deletion while attempts exist.
        long exam = newExam("DS");
        long session = newOpenSession(exam, "DS");
        // Dedicated learner with only an attempt referencing it.
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ds2','ds2@t.com','h','DS2')");
        newAttempt(session, u2, 1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id=" + u2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteExamQuestionRestrictedByAttemptQuestion() {
        long exam = newExam("DE");
        long session = newOpenSession(exam, "DE");
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        newAttemptQuestion(attempt, eq, 0);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_questions WHERE id=" + eq))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Executes an INSERT and returns the generated id via RETURNING. */
    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private boolean columnExists(String table, String column) {
        return 1 == jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name='" + table
                        + "' AND column_name='" + column + "'", Integer.class);
    }

    private boolean constraintExists(String name) {
        return 1 == jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='" + name + "'", Integer.class);
    }

    private boolean indexExists(String name) {
        return 1 == jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname='" + name + "'", Integer.class);
    }

    private int count(String table, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=" + id, Integer.class);
    }

    private long newExam(String code) {
        return insert("INSERT INTO exams (created_by_user_id, subject_id, code, title) VALUES ("
                + userId + "," + subjectId + ",'" + code + "','t')");
    }

    private long newExamQuestion(long examId, String type, int position) {
        long section = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES ("
                + examId + ",'Sec'," + position + ")");
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examId + "," + section + "," + questionId + "," + questionVersionId + ",'QC','" + type
                + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long newOpenSession(long examId, String code) {
        return insert("INSERT INTO exam_sessions (exam_id, code, title, status, "
                + "starts_at, ends_at, duration_minutes, created_by, opened_at) VALUES (" + examId + ",'"
                + code + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours',60," + userId + ",now())");
    }

    private long newAttempt(long sessionId, long attemptUserId, int number) {
        return insert("INSERT INTO attempts (exam_session_id, user_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + sessionId + "," + attemptUserId + ","
                + number + ",now(),now()+interval '1 hour')");
    }

    private void submit(long attemptId, String key) {
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='"
                + key + "' WHERE id=" + attemptId);
    }

    private long newAttemptQuestion(long attemptId, long examQuestionId, int order) {
        return insert("INSERT INTO attempt_questions (attempt_id, exam_question_id, question_type, default_points, "
                + "display_order) VALUES (" + attemptId + "," + examQuestionId + ",'SINGLE_CHOICE',1," + order + ")");
    }

    /** Creates an IN_PROGRESS attempt with one attempt_question and returns the attempt id. */
    private long newAttemptWithQuestion() {
        // Unique creator-scoped exam/session codes per call (some tests build several of these).
        long n = ++awSeq;
        long exam = newExam("AW" + n);
        long session = newOpenSession(exam, "AW" + n);
        long attempt = newAttempt(session, learnerId, 1);
        long eq = newExamQuestion(exam, "SINGLE_CHOICE", 0);
        newAttemptQuestion(attempt, eq, 0);
        return attempt;
    }

    /** Returns the single attempt_question id for an attempt created by {@link #newAttemptWithQuestion()}. */
    private long attemptQuestionOf(long attemptId) {
        return jdbc.queryForObject(
                "SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " ORDER BY id LIMIT 1", Long.class);
    }

    private long newGrade(long attemptId) {
        return insert("INSERT INTO grades (attempt_id, automatic_score, final_score, max_score, graded_at) VALUES ("
                + attemptId + ",5,5,10,now())");
    }

    private long newIdempotency(long attemptId, String key) {
        return insert("INSERT INTO idempotency_records (user_id, attempt_id, operation, idempotency_key, "
                + "response_status, response_body) VALUES (" + userId + "," + attemptId + ",'ATTEMPT_SUBMIT','" + key
                + "',200,'{\"attemptId\":" + attemptId + "}'::jsonb)");
    }
}
