package com.hhtuann.backend.schema;

import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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
 * Integration tests verifying the V9 Attempt/Grading migration and every DB-level
 * constraint (CHECK, composite same-school FK, composite ownership FK, unique, partial
 * unique, submission invariant, idempotency invariants) on a real PostgreSQL 17 instance
 * via Testcontainers. Mirrors {@code V8ExamSchemaIntegrationTests}: direct
 * {@link JdbcTemplate} SQL, id-via-RETURNING, {@code DataIntegrityViolationException}
 * assertions for rejected rows, and {@code pg_catalog} checks for named constraints/indexes.
 *
 * <p>The Spring-managed container migrates a <em>clean</em> DB to V9 at context start
 * (Flyway V1→V9), which also proves Hibernate {@code ddl-auto=validate} passes — there
 * are no V9 JPA entities yet, so validate only checks V1–V8 entity mappings against the
 * now-V9 schema (V9 tables are simply unmapped).
 *
 * <p>Staged-Flyway proof (migrate to V8 with data → continue to V9; V8 data unchanged;
 * V9 rerun is a no-op; checksums validate) lives in the dedicated
 * {@code V9AttemptSchemaStagedFlywayTest} on its own container.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V9AttemptSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    // Monotonic counter for unique owner-scoped exam/session codes within a single test.
    private long awSeq = 0;

    // School-A fixture ids (set up per test; rolled back after).
    private long userId;
    private long schoolId;
    private long gradeLevelId;
    private long subjectId;
    private long teacherProfileId;
    private long studentProfileId;
    private long bankId;
    private long questionId;
    private long questionVersionId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u9','u9@t.com','h','U9')");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('TS','Test School')");
        gradeLevelId = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL12','G12')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + schoolId + "," + gradeLevelId + ",'MATH','Math')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + userId + "," + schoolId + ",'TC1')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + userId + "," + schoolId + ",'SC1')");
        bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'B1','Bank')");
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
    void flywayAppliedV1ThroughV9() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN "
                        + "('1','2','3','4','5','6','7','8','9')", Integer.class);
        assertThat(n).isEqualTo(9);
        String current = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(current).isEqualTo("10");
    }

    @Test
    void allAttemptTablesExistAndNoOutOfScope() {
        for (String t : new String[]{"attempts", "attempt_questions", "attempt_answers",
                "grades", "grade_items", "idempotency_records"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // No out-of-scope Day-7 tables.
        for (String forbidden : new String[]{"attempt_events", "attempt_sessions", "proctor_assignments",
                "manual_overrides", "audit_log", "outbox_events", "notifications", "heartbeat",
                "attempt_question_versions"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + forbidden + "'", Integer.class))
                    .as("forbidden table %s must not exist", forbidden).isZero();
        }
    }

    @Test
    void v1ThroughV8TablesStillPresent() {
        for (String t : new String[]{"platform_metadata", "users", "roles", "permissions", "user_roles",
                "role_permissions", "refresh_sessions", "schools", "grade_levels", "subjects",
                "teacher_profiles", "student_profiles", "question_banks", "questions", "question_versions",
                "question_options", "exam_purposes", "exams", "exam_versions", "exam_sections",
                "exam_questions", "exam_question_options", "exam_sessions", "exam_session_participants"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("legacy table %s", t).isEqualTo(1);
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
                "fk_attempts_session_school", "fk_attempts_student_school", "fk_attempts_version",
                "chk_attempts_status", "chk_attempts_number", "chk_attempts_deadline",
                "chk_attempts_submission_invariant", "uk_attempts_session_student_number",
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
                "uk_attempts_one_active_per_session_student", "uk_attempts_student_submit_key",
                "idx_attempts_session_status", "idx_attempts_student", "idx_attempts_session_student",
                "idx_attempt_questions_exam"}) {
            assertThat(indexExists(name))
                    .as("index %s must exist", name).isTrue();
        }
    }

    // ============================================================
    // GROUP 3 — attempts: composite school FK + invariants
    // ============================================================

    private record SchoolFixture(long schoolId, long subjectId, long teacherProfileId, long studentProfileId) {}

    private SchoolFixture secondSchool() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('B','School B')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL2','G2')");
        long sub2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + s2 + "," + gl2 + ",'SUB2','S2')");
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ub','ub@t.com','h','UB')");
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + u2 + "," + s2 + ",'TC2')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + u2 + "," + s2 + ",'SC2')");
        return new SchoolFixture(s2, sub2, tp2, sp2);
    }

    @Test
    void attemptRejectsCrossSchoolSession() {
        SchoolFixture b = secondSchool();
        long examB = newExamIn(b.schoolId, b.subjectId, b.teacherProfileId, "EB");
        long vB = newPublishedVersionIn(b.schoolId, examB, 1);
        long sessionB = newOpenSessionIn(b.schoolId, vB, b.teacherProfileId, "SB");
        // Attempt claims school A but session is in school B -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, started_at, deadline_at) VALUES (" + schoolId + "," + sessionB
                        + "," + studentProfileId + "," + vB + ",1,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsCrossSchoolStudent() {
        SchoolFixture b = secondSchool();
        long exam = newExam("EA");
        long v = newPublishedVersion(exam, 1);
        long session = newOpenSession(v, "SA");
        // Attempt claims school A but student is in school B -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, started_at, deadline_at) VALUES (" + schoolId + "," + session
                        + "," + b.studentProfileId() + "," + v + ",1,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsInvalidStatus() {
        long v = newPublishedVersion(newExam("ST"), 1);
        long session = newOpenSession(v, "ST");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at) VALUES (" + schoolId + ","
                        + session + "," + studentProfileId + "," + v + ",1,'CREATED',now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsAttemptNumberZero() {
        long v = newPublishedVersion(newExam("NZ"), 1);
        long session = newOpenSession(v, "NZ");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, started_at, deadline_at) VALUES (" + schoolId + "," + session
                        + "," + studentProfileId + "," + v + ",0,now(),now()+interval '1 hour')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptRejectsDeadlineBeforeStarted() {
        long v = newPublishedVersion(newExam("DL"), 1);
        long session = newOpenSession(v, "DL");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, started_at, deadline_at) VALUES (" + schoolId + "," + session
                        + "," + studentProfileId + "," + v + ",1,now()+interval '2 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptNumberDuplicateRejected() {
        long v = newPublishedVersion(newExam("ND"), 1);
        long session = newOpenSession(v, "ND");
        newAttempt(session, studentProfileId, v, 1);
        assertThatThrownBy(() -> newAttempt(session, studentProfileId, v, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneInProgressPerSessionStudent() {
        long v = newPublishedVersion(newExam("OA"), 1);
        long session = newOpenSession(v, "OA");
        newAttempt(session, studentProfileId, v, 1);
        // Second IN_PROGRESS for the same (session, student) -> partial unique violation.
        assertThatThrownBy(() -> newAttempt(session, studentProfileId, v, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inProgressThenSubmittedForSameSessionStudentAccepted() {
        long v = newPublishedVersion(newExam("IS"), 1);
        long session = newOpenSession(v, "IS");
        long a1 = newAttempt(session, studentProfileId, v, 1);
        // Submit attempt 1, then a fresh IN_PROGRESS attempt 2 is allowed (one-active is IN_PROGRESS-scoped).
        submit(a1, "K1");
        long a2 = newAttempt(session, studentProfileId, v, 2);
        assertThat(a2).isPositive();
    }

    @Test
    void twoInProgressDifferentStudentsAccepted() {
        long v = newPublishedVersion(newExam("TS"), 1);
        long session = newOpenSession(v, "TS");
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('us2','us2@t.com','h','US2')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + u2 + "," + schoolId + ",'SC2')");
        newAttempt(session, studentProfileId, v, 1);
        long a2 = newAttempt(session, sp2, v, 1);
        assertThat(a2).isPositive();
    }

    // --- Submission invariant (D25) ---

    @Test
    void submissionInvariantInProgressWithSubmittedAtRejected() {
        long v = newPublishedVersion(newExam("S1"), 1);
        long session = newOpenSession(v, "S1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at) VALUES ("
                        + schoolId + "," + session + "," + studentProfileId + "," + v + ",1,'IN_PROGRESS',now(),"
                        + "now()+interval '1 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantInProgressWithKeyRejected() {
        long v = newPublishedVersion(newExam("S2"), 1);
        long session = newOpenSession(v, "S2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submission_idempotency_key) "
                        + "VALUES (" + schoolId + "," + session + "," + studentProfileId + "," + v + ",1,'IN_PROGRESS',"
                        + "now(),now()+interval '1 hour','K')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantSubmittedWithNullSubmittedAtRejected() {
        long v = newPublishedVersion(newExam("S3"), 1);
        long session = newOpenSession(v, "S3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submission_idempotency_key) "
                        + "VALUES (" + schoolId + "," + session + "," + studentProfileId + "," + v + ",1,'SUBMITTED',"
                        + "now(),now()+interval '1 hour','K')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantSubmittedWithNullKeyRejected() {
        long v = newPublishedVersion(newExam("S4"), 1);
        long session = newOpenSession(v, "S4");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at) VALUES ("
                        + schoolId + "," + session + "," + studentProfileId + "," + v + ",1,'SUBMITTED',now(),"
                        + "now()+interval '1 hour',now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void submissionInvariantGradedRequiresBothTimestampsAndKey() {
        long v = newPublishedVersion(newExam("S5"), 1);
        long session = newOpenSession(v, "S5");
        // GRADED with both set is accepted.
        long ok = insert("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, "
                + "attempt_number, status, started_at, deadline_at, submitted_at, submission_idempotency_key) VALUES ("
                + schoolId + "," + session + "," + studentProfileId + "," + v + ",1,'GRADED',now(),"
                + "now()+interval '1 hour',now(),'KG')");
        assertThat(ok).isPositive();
        // GRADED with null key rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, "
                        + "exam_version_id, attempt_number, status, started_at, deadline_at, submitted_at, "
                        + "submission_idempotency_key) VALUES (" + schoolId + "," + session + "," + studentProfileId
                        + "," + v + ",2,'GRADED',now(),now()+interval '1 hour',now(),NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullableSubmitKeysDoNotConflict() {
        // Two distinct sessions, same student, both IN_PROGRESS with NULL key -> no conflict (partial index).
        long v = newPublishedVersion(newExam("NK"), 1);
        long s1 = newOpenSession(v, "N1");
        long s2 = newOpenSession(v, "N2");
        long a1 = newAttempt(s1, studentProfileId, v, 1);
        long a2 = newAttempt(s2, studentProfileId, v, 1);
        assertThat(a1).isPositive();
        assertThat(a2).isPositive();
    }

    @Test
    void nonNullSubmitKeyDuplicatePerStudentRejected() {
        long v = newPublishedVersion(newExam("DK"), 1);
        long s1 = newOpenSession(v, "D1");
        long s2 = newOpenSession(v, "D2");
        long a1 = newAttempt(s1, studentProfileId, v, 1);
        submit(a1, "DUP-KEY");
        long a2 = newAttempt(s2, studentProfileId, v, 1);
        // Reusing the same submit key for a different attempt of the same student -> partial unique violation.
        assertThatThrownBy(() -> submit(a2, "DUP-KEY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 4 — attempt_questions
    // ============================================================

    @Test
    void attemptQuestionRejectsInvalidType() {
        long v = newPublishedVersion(newExam("QT"), 1);
        long session = newOpenSession(v, "QT");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'ESSAY',1,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsNonPositivePoints() {
        long v = newPublishedVersion(newExam("QP"), 1);
        long session = newOpenSession(v, "QP");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',0,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsNegativeDisplayOrder() {
        long v = newPublishedVersion(newExam("QO"), 1);
        long session = newOpenSession(v, "QO");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',1,-1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionRejectsOptionOrderNonArray() {
        long v = newPublishedVersion(newExam("QR"), 1);
        long session = newOpenSession(v, "QR");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attempt_questions (attempt_id, exam_question_id, "
                        + "question_type, default_points, display_order, option_order) VALUES (" + attempt + "," + eq
                        + ",'SINGLE_CHOICE',1,0,'{\"a\":1}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionDuplicateExamQuestionRejected() {
        long v = newPublishedVersion(newExam("QD"), 1);
        long session = newOpenSession(v, "QD");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        newAttemptQuestion(attempt, eq, 0);
        assertThatThrownBy(() -> newAttemptQuestion(attempt, eq, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptQuestionDuplicateDisplayOrderRejected() {
        long v = newPublishedVersion(newExam("QX"), 1);
        long session = newOpenSession(v, "QX");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eqA = newExamQuestion(v, "SINGLE_CHOICE", 0);
        // A second DISTINCT source question/version (else uk_exam_questions_version_source rejects first).
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bankId + ",'QXb',"
                + userId + ")");
        long q2v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c2',1,'{}'::jsonb,"
                + userId + ")");
        long section2 = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + v + ",'S2',1)");
        long eqB = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + v + "," + section2 + "," + q2 + "," + q2v + ",'QC','SINGLE_CHOICE','c2',1,1,'{}'::jsonb)");
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
        long v = newPublishedVersion(newExam("AC"), 1);
        long session = newOpenSession(v, "AC");
        long attemptA = newAttempt(session, studentProfileId, v, 1);
        // A second attempt is impossible for the same (session,student) while A is IN_PROGRESS,
        // so use a different student to create attemptB and its attempt_question.
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ac2','ac2@t.com','h','AC2')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + u2 + "," + schoolId + ",'AC2')");
        long attemptB = newAttempt(session, sp2, v, 1);
        long eqB = newExamQuestion(v, "SINGLE_CHOICE", 0);
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
        long v = newPublishedVersion(newExam("DR"), 1);
        long session = newOpenSession(v, "DR");
        newAttempt(session, studentProfileId, v, 1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_sessions WHERE id=" + session))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteStudentRestrictedByAttempt() {
        long v = newPublishedVersion(newExam("DS"), 1);
        long session = newOpenSession(v, "DS");
        // Dedicated student with only an attempt referencing it.
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('ds2','ds2@t.com','h','DS2')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + u2 + "," + schoolId + ",'DS2')");
        newAttempt(session, sp2, v, 1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM student_profiles WHERE id=" + sp2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteExamQuestionRestrictedByAttemptQuestion() {
        long v = newPublishedVersion(newExam("DE"), 1);
        long session = newOpenSession(v, "DE");
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
        newAttemptQuestion(attempt, eq, 0);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_questions WHERE id=" + eq))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteAttemptRestrictedByVersion() {
        long v = newPublishedVersion(newExam("DV"), 1);
        long session = newOpenSession(v, "DV");
        newAttempt(session, studentProfileId, v, 1);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_versions WHERE id=" + v))
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
        return newExamIn(schoolId, subjectId, teacherProfileId, code);
    }

    private long newExamIn(long school, long subject, long teacher, String code) {
        return insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                + school + "," + subject + "," + teacher + ",'" + code + "','t')");
    }

    private long newPublishedVersion(long examId, int n) {
        return newPublishedVersionIn(schoolId, examId, n);
    }

    private long newPublishedVersionIn(long school, long examId, int n) {
        return insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                + "published_at, created_by) VALUES (" + school + "," + examId + "," + n + ",'PUBLISHED',10,now(),"
                + userId + ")");
    }

    private long newExamQuestion(long versionId, String type, int position) {
        long section = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES ("
                + versionId + ",'Sec'," + position + ")");
        return insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + versionId + "," + section + "," + questionId + "," + questionVersionId + ",'QC','" + type
                + "','c',1," + position + ",'{}'::jsonb)");
    }

    private long newOpenSession(long versionId, String code) {
        return newOpenSessionIn(schoolId, versionId, teacherProfileId, code);
    }

    private long newOpenSessionIn(long school, long versionId, long teacher, String code) {
        return insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, created_by, opened_at) VALUES (" + school + "," + versionId + "," + teacher + ",'"
                + code + "','t','OPEN',now()-interval '1 hour',now()+interval '2 hours'," + userId + ",now())");
    }

    private long newAttempt(long sessionId, long studentId, long versionId, int number) {
        return insert("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, "
                + "attempt_number, started_at, deadline_at) VALUES (" + schoolId + "," + sessionId + "," + studentId + ","
                + versionId + "," + number + ",now(),now()+interval '1 hour')");
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
        // Unique owner-scoped exam/session codes per call (some tests build several of these).
        long n = ++awSeq;
        long v = newPublishedVersion(newExam("AW" + n), 1);
        long session = newOpenSession(v, "AW" + n);
        long attempt = newAttempt(session, studentProfileId, v, 1);
        long eq = newExamQuestion(v, "SINGLE_CHOICE", 0);
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
