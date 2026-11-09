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
 * Integration tests verifying the exam-schema constraints (CHECK, FK, unique,
 * partial unique, M1/M2 invariants) on a real PostgreSQL 17 instance via
 * Testcontainers, against the CURRENT head schema (V1–V25).
 *
 * <p>Since the user-centric refactor (V24/V25) the schema is user-owned:
 * no organizations / workspaces / workspace_memberships / schools /
 * teacher_profiles / student_profiles / exam_versions /
 * legacy_exam_content_map tables, and no school_id / workspace_id /
 * owner_teacher_id / student_profile_id columns on content tables. This
 * suite asserts that contract AND keeps the surviving constraint coverage:
 * direct {@link JdbcTemplate} SQL, id-via-RETURNING,
 * {@code DataIntegrityViolationException} assertions for rejected rows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V8ExamSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    // Per-test fixture ids (rolled back after each test).
    private long userId;
    private long gradeLevelId;
    private long subjectId;
    private long bankId;
    private long questionId;
    private long questionVersionId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u8','u8@t.com','h','U8')");
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
    // GROUP 1 — Migration (head = V25)
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
        assertThat(current).isEqualTo("31");
    }

    @Test
    void legacyOrganizationTablesAreGoneAfterV25() {
        // V25 drops the entire organization/workspace/school/profile era.
        for (String t : new String[]{"organizations", "workspaces", "workspace_memberships",
                "schools", "teacher_profiles", "student_profiles", "exam_versions",
                "legacy_exam_content_map"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("legacy table %s must be gone after V25", t).isZero();
        }
    }

    @Test
    void legacyProvenanceColumnsAreGoneAfterV25() {
        // V25 verification contract: no workspace_id / owner_teacher_id /
        // student_profile_id columns anywhere, and no school_id on the
        // content/catalog tables.
        Integer legacy = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE column_name IN "
                        + "('workspace_id','owner_teacher_id','student_profile_id')", Integer.class);
        assertThat(legacy).as("workspace/teacher/profile columns must be gone").isZero();

        Integer schoolScoped = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE column_name = 'school_id' "
                        + "AND table_name IN ('question_banks','exams','exam_sessions','attempts',"
                        + "'exam_session_participants','classrooms','classroom_members',"
                        + "'exam_session_classes','subjects','grade_levels','exam_purposes')", Integer.class);
        assertThat(schoolScoped).as("school_id must be gone from content/catalog tables").isZero();
    }

    @Test
    void creatorColumnsAndUniquesExist() {
        // User-centric ownership columns (V11/V12/V16/V17/V24).
        for (String[] col : new String[][]{
                {"question_banks", "created_by_user_id"},
                {"exams", "created_by_user_id"},
                {"classrooms", "owner_user_id"},
                {"exam_sessions", "created_by"},
                {"attempts", "user_id"},
                {"exam_session_participants", "user_id"},
                {"classroom_members", "user_id"}}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns WHERE table_name='" + col[0]
                            + "' AND column_name='" + col[1] + "'", Integer.class))
                    .as("column %s.%s", col[0], col[1]).isEqualTo(1);
        }
        // Creator-scoped code uniqueness (V24).
        for (String idx : new String[]{"uk_question_banks_creator_code_ci",
                "uk_exams_creator_code_ci", "uk_exam_sessions_creator_code_ci"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE indexname='" + idx + "'", Integer.class))
                    .as("unique index %s", idx).isEqualTo(1);
        }
    }

    @Test
    void allExamTablesExistAndNoOutOfScope() {
        for (String t : new String[]{"exam_purposes", "exams", "exam_sections",
                "exam_questions", "exam_question_options", "exam_sessions", "exam_session_participants",
                "classrooms", "classroom_members", "exam_session_classes"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // No out-of-scope tables.
        for (String forbidden : new String[]{"proctor_assignments",
                "session_announcements", "audit_log", "outbox_events", "import_jobs"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + forbidden + "'", Integer.class))
                    .as("forbidden table %s must not exist", forbidden).isZero();
        }
    }

    @Test
    void coreTablesStillPresent() {
        for (String t : new String[]{"platform_metadata", "users", "roles", "permissions", "user_roles",
                "role_permissions", "refresh_sessions", "grade_levels", "subjects",
                "question_banks", "questions", "question_versions", "question_options"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
    }

    @Test
    void cleanDbHasSeededGlobalCatalog() {
        // V24 seeds the global academic catalog on a clean database:
        // G10–G12 × 15 subjects and 4 exam purposes. Other suites may add
        // their own (unique-coded) catalog rows on the shared container, so
        // assert the SEED rows are present rather than exact totals.
        Integer gl = jdbc.queryForObject(
                "SELECT count(*) FROM grade_levels WHERE code IN ('G10','G11','G12')", Integer.class);
        Integer subjects = jdbc.queryForObject(
                "SELECT count(*) FROM subjects WHERE grade_level_id IN "
                        + "(SELECT id FROM grade_levels WHERE code IN ('G10','G11','G12'))", Integer.class);
        Integer purposes = jdbc.queryForObject(
                "SELECT count(*) FROM exam_purposes WHERE code IN ('MIDTERM','FINAL','QUIZ','PRACTICE')",
                Integer.class);
        assertThat(gl).isEqualTo(3);
        // at-least bound per the documented seed-not-totals strategy above
        // (attempt suites insert extra subjects on the shared container).
        assertThat(subjects).isGreaterThanOrEqualTo(45);
        assertThat(purposes).isEqualTo(4);
    }
    @Test
    void rolesAreExactlyStudentTeacherAndDormantModerator() {
        // V24 consolidated the role model to STUDENT/TEACHER; V26 adds the
        // dormant operator-provisioned COMMUNITY_MODERATOR (granted to nobody).
        assertThat(jdbc.queryForObject(
                "SELECT string_agg(code, ',' ORDER BY code) FROM roles", String.class))
                .isEqualTo("COMMUNITY_MODERATOR,STUDENT,TEACHER");
    }

    // ============================================================
    // GROUP 3 — Global catalog integrity (school scoping removed)
    // ============================================================

    @Test
    void examAcceptsGlobalSubject() {
        // Subjects are global catalog rows; any exam may reference any subject.
        long otherGl = insert("INSERT INTO grade_levels (code, name) VALUES ('GLX','GX')");
        long otherSubject = insert("INSERT INTO subjects (grade_level_id, code, name) "
                + "VALUES (" + otherGl + ",'SUBX','SX')");
        long examOk = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title) "
                + "VALUES (" + userId + "," + otherSubject + ",'OK','t')");
        assertThat(examOk).isPositive();
    }

    @Test
    void examRejectsMissingSubject() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exams (created_by_user_id, subject_id, code, title) "
                + "VALUES (" + userId + ",99999999,'X','t')"))
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
    void participantRejectsMissingSession() {
        // V17+: participants are user-based rows with a plain session FK;
        // a row pointing at a missing session is still DB-rejected.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (user_id, "
                        + "exam_session_id, added_by) VALUES (" + userId + ",99999999," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================
    // GROUP 4 — Creator-scoped code uniqueness (V24)
    // ============================================================

    @Test
    void examCodeSameCreatorDifferentCaseRejected() {
        newExam("CODE-1");
        assertThatThrownBy(() -> newExam("code-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void examCodeTwoCreatorsSameCodeAccepted() {
        long otherUser = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u2a','u2a@t.com','h','U2A')");
        long exam1 = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title) "
                + "VALUES (" + userId + "," + subjectId + ",'SHARED','t')");
        long exam2 = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title) "
                + "VALUES (" + otherUser + "," + subjectId + ",'SHARED','t')");
        assertThat(exam1).isPositive();
        assertThat(exam2).isPositive();
        // Creator-scoped uniqueness accepts the same code across different creators.
    }

    @Test
    void sessionCodeSameCreatorDifferentCaseRejected() {
        long exam = newExam("SC1");
        newSession(exam, "SESS-1");
        assertThatThrownBy(() -> newSession(exam, "sess-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionCodeTwoCreatorsSameCodeAccepted() {
        long exam = newExam("SC2");
        long otherUser = insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('u2b','u2b@t.com','h','U2B')");
        long s1 = insert("INSERT INTO exam_sessions (exam_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + exam + ",'SHARED','t',"
                + "now(),now()+interval '1 hour',60," + userId + ")");
        long s2 = insert("INSERT INTO exam_sessions (exam_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + exam + ",'SHARED','t',"
                + "now(),now()+interval '1 hour',60," + otherUser + ")");
        assertThat(s1).isPositive();
        assertThat(s2).isPositive();
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
        // Derive a unique creator-scoped code from the status so the multi-state acceptance test
        // (5 inserts, same creator) does not trip uk_exam_sessions_creator_code_ci.
        String code = "S-" + statusLit.replace("'", "");
        return insert("INSERT INTO exam_sessions (exam_id, code, title, status, "
                + "starts_at, ends_at, duration_minutes, created_by, opened_at, closed_at) VALUES (" + examId + ","
                + "'" + code + "','t'," + statusLit + ",now(),now()+interval '1 hour',60," + userId + ","
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
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_sessions (exam_id, "
                        + "code, title, starts_at, ends_at, duration_minutes, created_by) VALUES (" + exam + ","
                        + "'S','t',now()+interval '2 hour',now(),60," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionMaxAttemptsNegativeRejected() {
        long exam = newExam("SS3");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_sessions (exam_id, "
                        + "code, title, starts_at, ends_at, max_attempts, duration_minutes, created_by) VALUES (" + exam + ","
                        + "'S','t',now(),now()+interval '1 hour',-1,60," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionMaxAttemptsZeroAccepted() {
        // max_attempts = 0 means unlimited (absorbed from legacy V14); the DB CHECK is >= 0.
        long exam = newExam("SS3A");
        long sessionId = jdbc.queryForObject("INSERT INTO exam_sessions (exam_id, "
                        + "code, title, starts_at, ends_at, max_attempts, duration_minutes, created_by) VALUES (" + exam + ","
                        + "'S0','t',now(),now()+interval '1 hour',0,60," + userId
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
    void duplicateSessionUserRejected() {
        long exam = newExam("P1");
        long session = newSession(exam, "P1");
        newParticipant(session, userId);
        assertThatThrownBy(() -> newParticipant(session, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void participantInvalidStatusRejected() {
        long exam = newExam("P2");
        long session = newSession(exam, "P2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (exam_session_id, "
                        + "user_id, status, added_by) VALUES (" + session + "," + userId + ",'REMOVED'," + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m2EligibleWithNullBlockedAtAccepted() {
        long exam = newExam("M2A");
        long session = newSession(exam, "M2A");
        long p = newParticipant(session, userId);
        assertThat(p).isPositive();
    }

    @Test
    void m2EligibleWithNonNullBlockedAtRejected() {
        long exam = newExam("M2B");
        long session = newSession(exam, "M2B");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (exam_session_id, "
                        + "user_id, status, added_by, blocked_at) VALUES (" + session + "," + userId + ",'ELIGIBLE',"
                        + userId + ",now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m2BlockedWithNonNullBlockedAtAccepted() {
        long exam = newExam("M2C");
        long session = newSession(exam, "M2C");
        long p = insert("INSERT INTO exam_session_participants (user_id, exam_session_id, status, "
                + "added_by, blocked_at) VALUES (" + userId + "," + session + ",'BLOCKED'," + userId + ",now())");
        assertThat(p).isPositive();
    }

    @Test
    void m2BlockedWithNullBlockedAtRejected() {
        long exam = newExam("M2D");
        long session = newSession(exam, "M2D");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_session_participants (exam_session_id, "
                        + "user_id, status, added_by) VALUES (" + session + "," + userId + ",'BLOCKED'," + userId + ")"))
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
        return insert("INSERT INTO exams (created_by_user_id, subject_id, code, title) VALUES ("
                + userId + "," + subjectId + ",'" + code + "','t')");
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
        return insert("INSERT INTO exam_sessions (exam_id, code, title, "
                + "starts_at, ends_at, duration_minutes, created_by) VALUES (" + examId + ",'" + code + "','t',"
                + "now(),now()+interval '1 hour',60," + userId + ")");
    }

    private long newParticipant(long sessionId, long participantUserId) {
        return insert("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (" + participantUserId + "," + sessionId + "," + userId + ")");
    }
}
