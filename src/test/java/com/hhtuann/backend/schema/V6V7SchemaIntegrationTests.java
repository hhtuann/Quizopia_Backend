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
 * Integration tests verifying V6 + V7 migrations and all DB-level constraints
 * (CHECK, composite FK, unique). Uses {@link JdbcTemplate} to directly
 * exercise PostgreSQL constraints.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class V6V7SchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long schoolId;
    private long gradeLevelId;
    private long subjectId;
    private long teacherProfileId;
    private long bankId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name, status) "
                + "VALUES ('s-test','s@test.com','h','ST','ACTIVE')");
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('TS','Test School')");
        gradeLevelId = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL12','G12')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + schoolId + "," + gradeLevelId + ",'MATH','Math')");
        teacherProfileId = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + userId + "," + schoolId + ",'TC1')");
        bankId = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                + "VALUES (" + schoolId + "," + subjectId + "," + teacherProfileId + ",'B1','Bank')");
    }

    // -- Flyway + table existence ------------------------------------------

    @Test
    void flywayAppliedV1ThroughV7() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5','6','7')",
                Integer.class);
        assertThat(n).isEqualTo(7);
    }

    @Test
    void allV6V7TablesExist() {
        for (String t : new String[]{"schools","grade_levels","subjects","teacher_profiles","student_profiles",
                "question_banks","questions","question_versions","question_options"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
    }

    // -- question_type CHECK -----------------------------------------------

    @Test
    void typeAcceptsFourTypes() {
        // Non-NUMERIC types accept NULL answer_key; NUMERIC_FILL is tested
        // separately with a valid answer_key in answerKeyAcceptsValidNumber4.
        for (String type : new String[]{"SINGLE_CHOICE","MULTIPLE_CHOICE","TRUE_FALSE_MATRIX"}) {
            insertVersionForNewQuestion("T-" + type, type, "NULL");
        }
        insertVersionForNewQuestion("T-NUMERIC_FILL", "NUMERIC_FILL",
                "'{\"expectedAnswer\":\"1.25\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void typeRejectsTrueFalse() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("T-TF", "TRUE_FALSE", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void typeRejectsEssay() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("T-ESSAY", "ESSAY", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- default_points > 0 ------------------------------------------------

    @Test
    void pointsRejectsZero() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("P0", "SINGLE_CHOICE", "NULL", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pointsRejectsNegative() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("PN", "SINGLE_CHOICE", "NULL", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- option_key A–F ----------------------------------------------------

    @Test
    void optionKeyRejectsBeyondF() {
        long vid = insertVersionForNewQuestion("OPT-G", "SINGLE_CHOICE", "NULL");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_options (question_version_id, option_key, content, position) "
                        + "VALUES (" + vid + ",'G','Opt G',0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- School-scope composite FKs ----------------------------------------

    @Test
    void subjectFkRejectsGradeLevelFromDifferentSchool() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('S2','S2')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL2','G2')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO subjects (school_id, grade_level_id, code, name) "
                        + "VALUES (" + schoolId + "," + gl2 + ",'XS','X')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bankFkRejectsCrossSchoolSubject() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('S3','S3')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL3','G3')");
        long sub2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + s2 + "," + gl2 + ",'SUB2','S2')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                        + "VALUES (" + schoolId + "," + sub2 + "," + teacherProfileId + ",'XB','X')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bankFkRejectsCrossSchoolOwner() {
        long s2 = insert("INSERT INTO schools (code, name) VALUES ('S4','S4')");
        long gl2 = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + s2 + ",'GL4','G4')");
        long sub2 = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (" + s2 + "," + gl2 + ",'SUB3','S3')");
        long user2 = insert("INSERT INTO users (username, email, password_hash, display_name, status) "
                + "VALUES ('s-test-own','so@test.com','h','SO','ACTIVE')");
        long tp2 = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + user2 + "," + s2 + ",'TC2')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) "
                        + "VALUES (" + schoolId + "," + sub2 + "," + tp2 + ",'XB2','X')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- answer_key type-safe CHECK ----------------------------------------

    @Test
    void answerKeyAcceptsValidNumber4() {
        insertVersionForNewQuestion("AK-OK", "NUMERIC_FILL",
                "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void answerKeyAcceptsExpectedAnswerOnly() {
        // V12: only expectedAnswer is required.
        insertVersionForNewQuestion("AK-MIN", "NUMERIC_FILL", "'{\"expectedAnswer\":\"2.50\"}'::jsonb");
    }

    @Test
    void answerKeyIgnoresLegacyExtraKeys() {
        // Legacy 3-field literals still satisfy the relaxed CHECK.
        insertVersionForNewQuestion("AK-LEGACY", "NUMERIC_FILL",
                "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb");
    }

    @Test
    void answerKeyRejectsExpectedAnswerNumber() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("AK-NUM", "NUMERIC_FILL",
                "'{\"expectedAnswer\":2.50,\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void answerKeyRejectsWrongLength() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("AK-LEN", "NUMERIC_FILL",
                "'{\"expectedAnswer\":\"2.5\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void answerKeyRejectsComma() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("AK-COMMA", "NUMERIC_FILL",
                "'{\"expectedAnswer\":\"2,50\",\"requiredInputLength\":4,\"roundingInstruction\":\"r2\"}'::jsonb"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void answerKeyNonNullRejectedForNonNumeric() {
        assertThatThrownBy(() -> insertVersionForNewQuestion("AK-NN", "SINGLE_CHOICE",
                "'{\"expectedAnswer\":\"2.50\"}'::jsonb"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void metadataRejectsArray() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_versions (question_id, version_number, question_type, content, "
                        + "difficulty, default_points, answer_key, metadata, created_by) "
                        + "VALUES (" + newQuestion("META-ARR") + ", 1, 'SINGLE_CHOICE', 'c', 'MEDIUM', 1, NULL, "
                        + "'[1,2]'::jsonb, " + userId + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- Unique constraints ------------------------------------------------

    @Test
    void questionCodeUniqueCaseInsensitive() {
        newQuestion("UNIQ-CODE");
        assertThatThrownBy(() -> newQuestion("uniq-code"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teacherProfileUserIdUnique() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + userId + "," + schoolId + ",'DUP')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- Helpers ------------------------------------------------------------

    /** Executes an INSERT and returns the generated id via RETURNING. */
    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    /** Inserts a new question (unique code) and returns its id. */
    private long newQuestion(String code) {
        return insert("INSERT INTO questions (question_bank_id, code, created_by) "
                + "VALUES (" + bankId + ",'" + code + "'," + userId + ")");
    }

    /** Inserts a version for a new question; returns the version id. */
    private long insertVersionForNewQuestion(String code, String type, String answerKeySql) {
        return insertVersionForNewQuestion(code, type, answerKeySql, 1);
    }

    private long insertVersionForNewQuestion(String code, String type, String answerKeySql, int points) {
        long qid = newQuestion(code);
        return insert("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "difficulty, default_points, answer_key, metadata, created_by) "
                + "VALUES (" + qid + ",1,'" + type + "','c','MEDIUM'," + points + "," + answerKeySql + ","
                + "'{}'::jsonb," + userId + ")");
    }
}
