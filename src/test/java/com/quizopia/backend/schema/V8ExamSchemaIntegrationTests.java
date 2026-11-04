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
 * Integration tests verifying the V8 Exam migration and all DB-level constraints
 * (CHECK, composite FK, unique, partial unique, M1/M2 invariants) on a real
 * PostgreSQL 17 instance via Testcontainers. Mirrors the convention of
 * {@code V6V7SchemaIntegrationTests}: direct {@link JdbcTemplate} SQL, id-via-RETURNING,
 * {@code DataIntegrityViolationException} assertions for rejected rows.
 *
 * <p>The Spring-managed container migrates a <em>clean</em> DB to V8 at context start,
 * so the V8 purpose seed inserts 0 rows there (no schools yet). The dedicated-container
 * seed test (Group 2) proves the seed by migrating to V7 first, inserting a school, then
 * continuing to V8. Per-test fixtures insert their own purposes where needed.
 *
 * <p>This checkpoint has no exam JPA entities; Hibernate {@code ddl-auto=validate} is
 * therefore not exercised for exam tables here (entity mapping is a later checkpoint).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V8ExamSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

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
                + "VALUES ('u8','u8@t.com','h','U8')");
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
    // GROUP 1 — Migration
    // ============================================================

    @Test
    void flywayAppliedV1ThroughV8() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN "
                        + "('1','2','3','4','5','6','7','8')", Integer.class);
        assertThat(n).isEqualTo(8);
        // V1–V8 are all applied; Phase 1 Task A adds V10–V12 (workspace foundation),
        // Task B adds V13–V15 (exam redesign: expand/backfill/contract), and issue #4
        // P0-1 adds V16 (user-based attempts).
        String current = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(current).isEqualTo("19");
    }

    @Test
    void allExamTablesExistAndNoOutOfScope() {
        for (String t : new String[]{"exam_purposes", "exams", "exam_versions", "exam_sections",
                "exam_questions", "exam_question_options", "exam_sessions", "exam_session_participants",
                "organizations", "workspaces", "workspace_memberships", "legacy_exam_content_map"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // No out-of-scope exam tables. NOTE: `notifications` is now V9 (consolidated);
        // `attempts` is V7; `classrooms`/`classroom_members`/`exam_session_classes` are V8.
        for (String forbidden : new String[]{"proctor_assignments",
                "session_announcements", "audit_log", "outbox_events", "import_jobs"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + forbidden + "'", Integer.class))
                    .as("forbidden table %s must not exist", forbidden).isZero();
        }
    }

    @Test
    void v1ThroughV7TablesStillPresent() {
        for (String t : new String[]{"platform_metadata", "users", "roles", "permissions", "user_roles",
                "role_permissions", "refresh_sessions", "schools", "grade_levels", "subjects",
                "teacher_profiles", "student_profiles", "question_banks", "questions",
                "question_versions", "question_options"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("legacy table %s", t).isEqualTo(1);
        }
    }

    // ============================================================
    // GROUP 2 — Purpose seed (dedicated container, staged Flyway)
    // ============================================================

    /**
     * The Spring-managed container is a clean (Testcontainers-fresh) DB migrated to V8 at
     * context start, when no schools existed yet, so the V8 seed inserted 0 purposes. The
     * {@code @BeforeEach} school is created after migration and is therefore not seeded
     * (proving the seed is migration-time only; per-school idempotent seeding for schools
     * created later is DemoDataSeeder's responsibility). The full staged proof (migrate to
     * V7 -> insert school -> continue to V8 -> 4 purposes) lives in the dedicated
     * {@code V8PurposeSeedStagedFlywayTest} (own container, no shared fixture/pollution).
     */
    @Test
    void cleanDbMigratesV8WithZeroPurposes() {
        Integer purposeCount = jdbc.queryForObject("SELECT count(*) FROM exam_purposes", Integer.class);
        assertThat(purposeCount).isZero();
    }

    // ============================================================
    // GROUP 3 — Composite school integrity (cross-school rejected)
    // ============================================================

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

    private record SchoolFixture(long schoolId, long subjectId, long teacherProfileId, long studentProfileId) {}

    @Test
    void examRejectsCrossSchoolSubject() {
        SchoolFixture b = secondSchool();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + schoolId + "," + b.subjectId() + "," + teacherProfileId + ",'X','t')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examRejectsCrossSchoolOwner() {
        SchoolFixture b = secondSchool();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + b.teacherProfileId() + ",'X','t')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examRejectsCrossSchoolPurpose() {
        SchoolFixture b = secondSchool();
        long purposeB = insert("INSERT INTO exam_purposes (school_id, code, title) VALUES (" + b.schoolId() + ",'PB','b')");
        // Purpose from another school must be rejected (RESTRICT composite FK on school match).
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exams (school_id, subject_id, owner_teacher_id, purpose_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + "," + purposeB + ",'X','t')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examAcceptsSameSchoolPurpose() {
        long purposeA = insert("INSERT INTO exam_purposes (school_id, code, title) VALUES (" + schoolId + ",'PA','a')");
        long examOk = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, purpose_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + "," + purposeA + ",'OK','t')");
        assertThat(examOk).isPositive();
    }

    @Test
    void examVersionRejectsSchoolNotMatchingExam() {
        SchoolFixture b = secondSchool();
        long examA = newExam("EV1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, created_by) "
                + "VALUES (" + b.schoolId() + "," + examA + ",1," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examQuestionRejectsSectionFromDifferentExam() {
        long exam = newExam("EQ1");
        long secA = newSection(exam, 0);
        long exam2 = newExam("EQ2");
        // Section belongs to exam, not exam2 -> composite FK violation.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam2 + "," + secA + "," + questionId + ","
                        + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // (Same-exam section acceptance is exercised by many other tests that create questions.)
    }

    @Test
    void examQuestionRejectsVersionFromDifferentQuestion() {
        // H1: composite FK fk_exam_questions_source_pair requires the pinned source version to belong
        // to the source question. qA (questionId) + vB (a version of qB) must be REJECTED.
        long exam = newExam("H1");
        long sec = newSection(exam, 0);
        long qB = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'H1B'," + userId + ")");
        long vB = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + qB + ",1,'SINGLE_CHOICE','cb',1,'{}'::jsonb,"
                + userId + ")");
        // All other fields are valid; the ONLY violation is the source-pair provenance composite FK.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam + "," + sec + "," + questionId + "," + vB
                        + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_exam_questions_source_pair");
    }

    @Test
    void sessionRejectsCrossSchoolOwner() {
        SchoolFixture b = secondSchool();
        long exam = newExam("SO1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, "
                        + "code, title, starts_at, ends_at, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + ","
                        + b.teacherProfileId() + ",'X','t',now(),now()+interval '1 hour',60," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void participantRejectsCrossSchoolSession() {
        SchoolFixture b = secondSchool();
        long examB = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + b.schoolId() + "," + b.subjectId() + "," + b.teacherProfileId() + ",'PB','t')");
        long sessionB = insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + b.schoolId() + "," + examB + "," + b.teacherProfileId()
                + ",'SB','t',now(),now()+interval '1 hour',60," + userId + ")");
        // V17: the legacy same-school composite session FK was replaced by a
        // simple session FK (user-based rows may be school-less). A row whose
        // school contradicts the session's school is no longer DB-rejected —
        // cross-school assignment is enforced at the service layer. The
        // schema-level guard that REMAINS is session existence: a participant
        // row pointing at a missing session is still rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (user_id, school_id, exam_session_id, "
                        + "student_profile_id, added_by) VALUES ((SELECT user_id FROM student_profiles WHERE id = "
                        + studentProfileId + ")," + schoolId + ",99999999," + studentProfileId
                        + "," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void participantRejectsCrossSchoolStudent() {
        SchoolFixture b = secondSchool();
        long exam = newExam("PS1");
        long session = newSession(exam, "SS1");
        // V17: student-side same-school composite FK replaced by a simple
        // profile FK (provenance only); cross-school ADD is enforced at the
        // service layer (addParticipants same-school validation). The
        // schema-level guard that REMAINS is profile existence.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (user_id, school_id, exam_session_id, "
                        + "student_profile_id, added_by) VALUES ((SELECT user_id FROM student_profiles WHERE id = "
                        + b.studentProfileId() + ")," + schoolId + "," + session + ",99999999"
                        + "," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 4 — Owner-scoped code uniqueness
    // ============================================================

    @Test
    void examCodeSameOwnerDifferentCaseRejected() {
        newExam("CODE-1");
        assertThatThrownBy(() -> newExam("code-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examCodeTwoOwnersSameSchoolSameCodeAccepted() {
        SchoolFixture b = secondSchool();
        // Insert a second teacher in school A.
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u2a','u2a@t.com','h','U2A')");
        long tp2a = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + u2 + "," + schoolId + ",'TC2A')");
        long exam1 = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'SHARED','t')");
        long exam2 = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) "
                + "VALUES (" + schoolId + "," + subjectId + "," + tp2a + ",'SHARED','t')");
        assertThat(exam1).isPositive();
        assertThat(exam2).isPositive();
        // School-scoped uniqueness would have rejected the second insert; it is accepted here.
        assertThat(b.schoolId()).isNotEqualTo(schoolId);
    }

    @Test
    void sessionCodeSameOwnerDifferentCaseRejected() {
        long exam = newExam("SC1");
        newSession(exam, "SESS-1");
        assertThatThrownBy(() -> newSession(exam, "sess-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionCodeTwoOwnersSameSchoolSameCodeAccepted() {
        long exam = newExam("SC2");
        long u2 = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u2b','u2b@t.com','h','U2B')");
        long tp2a = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + u2 + "," + schoolId + ",'TC2B')");
        long s1 = insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + "," + teacherProfileId
                + ",'SHARED','t',now(),now()+interval '1 hour',60," + userId + ")");
        long s2 = insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + "," + tp2a
                + ",'SHARED','t',now(),now()+interval '1 hour',60," + userId + ")");
        assertThat(s1).isPositive();
        assertThat(s2).isPositive();
    }

    // ============================================================
    // GROUP 5 — Version constraints (exam_versions: retained legacy
    // audit table after the Task B redesign; its own CHECK/unique
    // constraints still apply to audit inserts)
    // ============================================================

    @Test
    void onlyOneDraftPerExam() {
        long exam = newExam("D1");
        newDraftVersion(exam, 1);
        assertThatThrownBy(() -> newDraftVersion(exam, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void multiplePublishedVersionsAllowed() {
        long exam = newExam("D2");
        long p1 = newPublishedVersion(exam, 1);
        long p2 = newPublishedVersion(exam, 2);
        assertThat(p1).isPositive();
        assertThat(p2).isPositive();
    }

    @Test
    void versionNumberUniquePerExam() {
        long exam = newExam("D3");
        newDraftVersion(exam, 1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "status, total_points, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'PUBLISHED',1,now()," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void durationDefaultsTo60() {
        long exam = newExam("D4");
        long v = newDraftVersion(exam, 1);
        Integer d = jdbc.queryForObject("SELECT duration_minutes FROM exam_versions WHERE id=" + v, Integer.class);
        assertThat(d).isEqualTo(60);
    }

    @Test
    void durationNonPositiveRejected() {
        long exam = newExam("D5");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "duration_minutes, created_by) VALUES (" + schoolId + "," + exam + ",1,0," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tfLadderExactObjectAccepted() {
        long exam = newExam("TF1");
        long v = newDraftVersion(exam, 1);
        Integer stored = jdbc.queryForObject(
                "SELECT (tf_matrix_scoring = '{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)::int "
                        + "FROM exam_versions WHERE id=" + v, Integer.class);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void tfLadderWrongValueRejected() {
        long exam = newExam("TF2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "tf_matrix_scoring, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":99}'::jsonb," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tfLadderExtraKeyRejected() {
        long exam = newExam("TF3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "tf_matrix_scoring, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100,\"5\":100}'::jsonb," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tfLadderStringNumberRejected() {
        long exam = newExam("TF4");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "tf_matrix_scoring, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'{\"0\":\"0\",\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tfLadderMissingKeyRejected() {
        long exam = newExam("TF5");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "tf_matrix_scoring, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50}'::jsonb," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedInvariantDraftWithPublishedAtRejected() {
        long exam = newExam("PI1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "status, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,'DRAFT',now(),"
                        + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedInvariantPublishedWithNullPublishedAtRejected() {
        long exam = newExam("PI2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "status, total_points, created_by) VALUES (" + schoolId + "," + exam + ",1,'PUBLISHED',10,"
                        + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedInvariantPublishedZeroPointsRejected() {
        long exam = newExam("PI3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_versions (school_id, exam_id, version_number, "
                        + "status, total_points, published_at, created_by) VALUES (" + schoolId + "," + exam + ",1,"
                        + "'PUBLISHED',0,now()," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedInvariantPositivePointsAccepted() {
        long exam = newExam("PI4");
        long v = newPublishedVersion(exam, 1);
        assertThat(v).isPositive();
    }

    // ============================================================
    // GROUP 6 — Question snapshot
    // ============================================================

    @Test
    void sectionPositionDuplicateInSameExamRejected() {
        long exam = newExam("Q1");
        newSection(exam, 0);
        assertThatThrownBy(() -> newSection(exam, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void questionPositionDuplicateInSameSectionRejected() {
        long exam = newExam("Q2");
        long sec = newSection(exam, 0);
        newChoiceQuestion(exam, sec, 0);
        assertThatThrownBy(() -> newChoiceQuestion(exam, sec, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void questionPositionZeroInDifferentSectionsAccepted() {
        long exam = newExam("Q3");
        long secA = newSection(exam, 0);
        long secB = newSection(exam, 1);
        // Two DISTINCT source questions (same source twice in one exam would trip
        // uk_exam_questions_exam_source); both at local position 0 in different sections.
        long qa = newChoiceQuestion(exam, secA, 0);
        long q2 = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'Q3b'," + userId + ")");
        long q2v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q2 + ",1,'SINGLE_CHOICE','c2',1,'{}'::jsonb,"
                + userId + ")");
        long qb = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + exam + "," + secB + "," + q2 + "," + q2v + ",'QC','SINGLE_CHOICE','c2',1,0,'{}'::jsonb)");
        assertThat(qa).isPositive();
        assertThat(qb).isPositive();
    }

    @Test
    void duplicateSourceQuestionInSameExamRejected() {
        long exam = newExam("Q4");
        long sec = newSection(exam, 0);
        newChoiceQuestion(exam, sec, 0);
        assertThatThrownBy(() -> newChoiceQuestion(exam, sec, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSourceQuestionInDifferentExamsAccepted() {
        long exam1 = newExam("Q5");
        long exam2 = newExam("Q5B");
        long sec1 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam1 + ",'s',0)");
        long sec2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam2 + ",'s',0)");
        long q1 = newChoiceQuestion(exam1, sec1, 0);
        long q2 = newChoiceQuestion(exam2, sec2, 0);
        assertThat(q1).isPositive();
        assertThat(q2).isPositive();
    }

    @Test
    void sourceQuestionIdNullRejected() {
        long exam = newExam("Q6");
        long sec = newSection(exam, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_version_id, question_code, question_type, content, default_points, position, "
                        + "metadata) VALUES (" + exam + "," + sec + "," + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,"
                        + "'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examSectionIdNullRejected() {
        long exam = newExam("Q7");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam + ",NULL," + questionId + ","
                        + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidQuestionTypeRejected() {
        long exam = newExam("Q8");
        long sec = newSection(exam, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam + "," + sec + "," + questionId + ","
                        + questionVersionId + ",'QC','ESSAY','c',1,0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void difficultyNullAccepted() {
        long exam = newExam("Q9");
        long sec = newSection(exam, 0);
        long q = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + exam + "," + sec + "," + questionId + "," + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,"
                + "'{}'::jsonb)");
        assertThat(q).isPositive();
    }

    @Test
    void invalidDifficultyRejected() {
        long exam = newExam("Q10");
        long sec = newSection(exam, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, difficulty, position, metadata) VALUES (" + exam + "," + sec + "," + questionId
                        + "," + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,'IMPOSSIBLE',0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void metadataObjectAccepted() {
        long exam = newExam("Q11");
        long sec = newSection(exam, 0);
        long q = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + exam + "," + sec + "," + questionId + "," + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,"
                + "'{\"tag\":\"x\"}'::jsonb)");
        assertThat(q).isPositive();
    }

    @Test
    void metadataArrayRejected() {
        long exam = newExam("Q12");
        long sec = newSection(exam, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam + "," + sec + "," + questionId + ","
                        + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,'[1,2]'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 7 — Numeric answer key (type-safe, mirror V7)
    // ============================================================

    private record NumSource(long questionId, long versionId) {}

    private NumSource numericSource() {
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'QNUM'," + userId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, answer_key, metadata, created_by) VALUES (" + q + ",1,'NUMERIC_FILL','n',1,"
                + "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb,"
                + "'{}'::jsonb," + userId + ")");
        return new NumSource(q, v);
    }

    private void assertNumericAccepted(String answerKeyLiteral) {
        long exam = newExam("N-OK");
        long sec = newSection(exam, 0);
        NumSource src = numericSource();
        long q = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, "
                + "answer_key, metadata) VALUES (" + exam + "," + sec + "," + src.questionId() + "," + src.versionId()
                + ",'QC','NUMERIC_FILL','n',1,0," + answerKeyLiteral + ",'{}'::jsonb)");
        assertThat(q).isPositive();
    }

    private void assertNumericRejected(String answerKeyLiteral) {
        long exam = newExam("N-BAD");
        long sec = newSection(exam, 0);
        NumSource src = numericSource();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, answer_key, metadata) VALUES (" + exam + "," + sec + ","
                        + src.questionId() + "," + src.versionId() + ",'QC','NUMERIC_FILL','n',1,0," + answerKeyLiteral
                        + ",'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void numericAccepts2_50() {
        assertNumericAccepted("'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void numericAccepts02_5() {
        assertNumericAccepted("'{\"expectedAnswer\":\"02.5\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void numericAcceptsNegative() {
        assertNumericAccepted("'{\"expectedAnswer\":\"-1.2\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void numericAccepts1_25() {
        assertNumericAccepted("'{\"expectedAnswer\":\"1.25\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void numericAcceptsExpectedAnswerOnly() {
        // V12: only expectedAnswer is required (requiredInputLength/roundingInstruction removed).
        assertNumericAccepted("'{\"expectedAnswer\":\"2.50\"}'::jsonb");
    }

    @Test
    void numericRejectsExpectedAnswerWrongLength() {
        assertNumericRejected("'{\"expectedAnswer\":\"2.5\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void numericRejectsCommaInExpectedAnswer() {
        assertNumericRejected("'{\"expectedAnswer\":\"2,50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void choiceWithNonNullAnswerKeyRejected() {
        long exam = newExam("N-CH");
        long sec = newSection(exam, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, answer_key, metadata) VALUES (" + exam + "," + sec + "," + questionId
                        + "," + questionVersionId + ",'QC','SINGLE_CHOICE','c',1,0,"
                        + "'{\"expectedAnswer\":\"2.50\"}'::jsonb,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void numericWithNullAnswerKeyRejected() {
        long exam = newExam("N-NULL");
        long sec = newSection(exam, 0);
        NumSource src = numericSource();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_questions (exam_id, exam_section_id, "
                        + "source_question_id, source_question_version_id, question_code, question_type, content, "
                        + "default_points, position, metadata) VALUES (" + exam + "," + sec + "," + src.questionId() + ","
                        + src.versionId() + ",'QC','NUMERIC_FILL','n',1,0,'{}'::jsonb)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 8 — Options
    // ============================================================

    @Test
    void optionInvalidKeyRejected() {
        long exam = newExam("O1");
        long sec = newSection(exam, 0);
        long eq = newChoiceQuestion(exam, sec, 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, "
                        + "position) VALUES (" + eq + ",'G','o',0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optionDuplicateKeyRejected() {
        long exam = newExam("O2");
        long sec = newSection(exam, 0);
        long eq = newChoiceQuestion(exam, sec, 0);
        newOption(eq, "A", 0);
        assertThatThrownBy(() -> newOption(eq, "A", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optionDuplicatePositionRejected() {
        long exam = newExam("O3");
        long sec = newSection(exam, 0);
        long eq = newChoiceQuestion(exam, sec, 0);
        newOption(eq, "A", 0);
        assertThatThrownBy(() -> newOption(eq, "B", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 9 — Session + M1 (status<->timestamps)
    // ============================================================

    private long insertSessionWith(long examId, String statusLit, String openedLit, String closedLit) {
        // Derive a unique owner-scoped code from the status so the multi-state acceptance test
        // (5 inserts, same owner) does not trip uk_exam_sessions_owner_code_ci.
        String code = "S-" + statusLit.replace("'", "");
        return insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, status, "
                + "starts_at, ends_at, duration_minutes, created_by, opened_at, closed_at) VALUES (" + schoolId + "," + examId + ","
                + teacherProfileId + ",'" + code + "','t'," + statusLit + ",now(),now()+interval '1 hour',60," + userId + ","
                + openedLit + "," + closedLit + ")");
    }

    @Test
    void sessionInvalidStatusRejected() {
        long exam = newExam("SS1");
        assertThatThrownBy(() -> insertSessionWith(exam, "'BOGUS'", "NULL", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionEndsBeforeStartsRejected() {
        long exam = newExam("SS2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, "
                        + "code, title, starts_at, ends_at, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + ","
                        + teacherProfileId + ",'S','t',now()+interval '2 hour',now(),60," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionMaxAttemptsNegativeRejected() {
        long exam = newExam("SS3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, "
                        + "code, title, starts_at, ends_at, max_attempts, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + ","
                        + teacherProfileId + ",'S','t',now(),now()+interval '1 hour',-1,60," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionMaxAttemptsZeroAccepted() {
        // max_attempts = 0 means unlimited (absorbed from legacy V14); the DB CHECK is >= 0.
        long exam = newExam("SS3A");
        long sessionId = jdbc.queryForObject("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, "
                        + "code, title, starts_at, ends_at, max_attempts, duration_minutes, created_by) VALUES (" + schoolId + "," + exam + ","
                        + teacherProfileId + ",'S0','t',now(),now()+interval '1 hour',0,60," + userId
                        + ") RETURNING id", Long.class);
        assertThat(sessionId).isPositive();
    }

    // M1 consistency rejections.
    @Test
    void m1DraftWithOpenedAtRejected() {
        long exam = newExam("M1A");
        assertThatThrownBy(() -> insertSessionWith(exam, "'DRAFT'", "now()", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1ScheduledWithOpenedAtRejected() {
        long exam = newExam("M1B");
        assertThatThrownBy(() -> insertSessionWith(exam, "'SCHEDULED'", "now()", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1ScheduledWithClosedAtRejected() {
        long exam = newExam("M1C");
        assertThatThrownBy(() -> insertSessionWith(exam, "'SCHEDULED'", "NULL", "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1OpenMissingOpenedAtRejected() {
        long exam = newExam("M1D");
        assertThatThrownBy(() -> insertSessionWith(exam, "'OPEN'", "NULL", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1OpenWithClosedAtRejected() {
        long exam = newExam("M1E");
        assertThatThrownBy(() -> insertSessionWith(exam, "'OPEN'", "now()", "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1ClosedMissingOpenedAtRejected() {
        long exam = newExam("M1F");
        assertThatThrownBy(() -> insertSessionWith(exam, "'CLOSED'", "NULL", "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1ClosedMissingClosedAtRejected() {
        long exam = newExam("M1G");
        assertThatThrownBy(() -> insertSessionWith(exam, "'CLOSED'", "now()", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1CancelledWithOpenedAtRejected() {
        long exam = newExam("M1H");
        assertThatThrownBy(() -> insertSessionWith(exam, "'CANCELLED'", "now()", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1CancelledWithClosedAtRejected() {
        long exam = newExam("M1I");
        assertThatThrownBy(() -> insertSessionWith(exam, "'CANCELLED'", "NULL", "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m1AllValidStatesAccepted() {
        long exam = newExam("M1J");
        assertThat(insertSessionWith(exam, "'DRAFT'", "NULL", "NULL")).isPositive();
        assertThat(insertSessionWith(exam, "'SCHEDULED'", "NULL", "NULL")).isPositive();
        assertThat(insertSessionWith(exam, "'OPEN'", "now()", "NULL")).isPositive();
        assertThat(insertSessionWith(exam, "'CLOSED'", "now()", "now()")).isPositive();
        assertThat(insertSessionWith(exam, "'CANCELLED'", "NULL", "NULL")).isPositive();
    }

    // ============================================================
    // GROUP 10 — Participants + M2 (status<->blocked_at)
    // ============================================================

    @Test
    void duplicateSessionStudentRejected() {
        long exam = newExam("P1");
        long session = newSession(exam, "P1");
        newParticipant(session, studentProfileId);
        assertThatThrownBy(() -> newParticipant(session, studentProfileId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void participantInvalidStatusRejected() {
        long exam = newExam("P2");
        long session = newSession(exam, "P2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, "
                        + "student_profile_id, status, added_by) VALUES (" + schoolId + "," + session + ","
                        + studentProfileId + ",'REMOVED'," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m2EligibleWithNullBlockedAtAccepted() {
        long exam = newExam("M2A");
        long session = newSession(exam, "M2A");
        long p = newParticipant(session, studentProfileId);
        assertThat(p).isPositive();
    }

    @Test
    void m2EligibleWithNonNullBlockedAtRejected() {
        long exam = newExam("M2B");
        long session = newSession(exam, "M2B");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, "
                        + "student_profile_id, status, added_by, blocked_at) VALUES (" + schoolId + "," + session + ","
                        + studentProfileId + ",'ELIGIBLE'," + userId + ",now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m2BlockedWithNonNullBlockedAtAccepted() {
        long exam = newExam("M2C");
        long session = newSession(exam, "M2C");
        long p = insert("INSERT INTO exam_session_participants (user_id, school_id, exam_session_id, student_profile_id, status, "
                + "added_by, blocked_at) VALUES ((SELECT user_id FROM student_profiles WHERE id = " + studentProfileId + ")," + schoolId + "," + session + "," + studentProfileId + ",'BLOCKED',"
                + userId + ",now())");
        assertThat(p).isPositive();
    }

    @Test
    void m2BlockedWithNullBlockedAtRejected() {
        long exam = newExam("M2D");
        long session = newSession(exam, "M2D");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, "
                        + "student_profile_id, status, added_by) VALUES (" + schoolId + "," + session + ","
                        + studentProfileId + ",'BLOCKED'," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Executes an INSERT and returns the generated id via RETURNING. */
    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private long newExam(String code) {
        return insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                + schoolId + "," + subjectId + "," + teacherProfileId + ",'" + code + "','t')");
    }

    /** Legacy audit-table insert (exam_versions is retained for audit; no runtime dependency). */
    private long newDraftVersion(long examId, int versionNumber) {
        return insert("INSERT INTO exam_versions (school_id, exam_id, version_number, created_by) VALUES ("
                + schoolId + "," + examId + "," + versionNumber + "," + userId + ")");
    }

    /** Legacy audit-table insert (exam_versions is retained for audit; no runtime dependency). */
    private long newPublishedVersion(long examId, int versionNumber) {
        return newPublishedVersionIn(schoolId, examId, versionNumber);
    }

    /** Legacy audit-table insert (exam_versions is retained for audit; no runtime dependency). */
    private long newPublishedVersionIn(long school, long examId, int versionNumber) {
        return insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, "
                + "published_at, created_by) VALUES (" + school + "," + examId + "," + versionNumber + ",'PUBLISHED',10,"
                + "now()," + userId + ")");
    }

    private long newSection(long examId, int position) {
        return insert("INSERT INTO exam_sections (exam_id, title, position) VALUES ("
                + examId + ",'Sec'," + position + ")");
    }

    private long newChoiceQuestion(long examId, long sectionId, int position) {
        return insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, metadata) "
                + "VALUES (" + examId + "," + sectionId + "," + questionId + "," + questionVersionId
                + ",'QC','SINGLE_CHOICE','c',1," + position + ",'{}'::jsonb)");
    }

    private long newOption(long questionId, String key, int position) {
        return insert("INSERT INTO exam_question_options (exam_question_id, option_key, content, position) VALUES ("
                + questionId + ",'" + key + "','o'," + position + ")");
    }

    private long newSession(long examId, String code) {
        return insert("INSERT INTO exam_sessions (school_id, exam_id, owner_teacher_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + schoolId + "," + examId + "," + teacherProfileId
                + ",'" + code + "','t',now(),now()+interval '1 hour',60," + userId + ")");
    }

    private long newParticipant(long sessionId, long studentId) {
        return insert("INSERT INTO exam_session_participants (user_id, school_id, exam_session_id, student_profile_id, added_by) "
                + "VALUES ((SELECT user_id FROM student_profiles WHERE id = " + studentId + ")," + schoolId + "," + sessionId + "," + studentId + "," + userId + ")");
    }
}
