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
    private long gradeLevelId;
    private long subjectId;
    private long bankId;

    @BeforeEach
    void setUp() {
        userId = insert("INSERT INTO users (username, email, password_hash, display_name, status) "
                + "VALUES ('s-test','s@test.com','h','ST','ACTIVE')");
        gradeLevelId = insert("INSERT INTO grade_levels (code, name) VALUES ('GL12','G12')");
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) "
                + "VALUES (" + gradeLevelId + ",'MATH','Math')");
        bankId = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + userId + "," + subjectId + ",'B1','Bank')");
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
        for (String t : new String[]{"grade_levels","subjects",
                "question_banks","questions","question_versions","question_options"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("table %s", t).isEqualTo(1);
        }
        // The school-era tables are gone after V25.
        for (String t : new String[]{"schools","teacher_profiles","student_profiles"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name='" + t + "'", Integer.class))
                    .as("legacy table %s", t).isZero();
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

    // -- Global catalog FKs (school scoping removed by V24/V25) ------------

    @Test
    void bankAcceptsAnyGlobalSubject() {
        // Subjects form one global catalog; any bank may reference any subject.
        long gl2 = insert("INSERT INTO grade_levels (code, name) VALUES ('GL2','G2')");
        long sub2 = insert("INSERT INTO subjects (grade_level_id, code, name) "
                + "VALUES (" + gl2 + ",'SUB2','S2')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + userId + "," + sub2 + ",'XB','X')");
        assertThat(bank).isPositive();
    }

    @Test
    void bankRejectsMissingSubject() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                        + "VALUES (" + userId + ",99999999,'XB','X')"))
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
    void bankCodeSameCreatorDifferentCaseRejected() {
        // V24: creator-scoped code uniqueness (uk_question_banks_creator_code_ci).
        assertThatThrownBy(() -> insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + userId + "," + subjectId + ",'b1','Dup')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bankCodeTwoCreatorsSameCodeAccepted() {
        long user2 = insert("INSERT INTO users (username, email, password_hash, display_name, status) "
                + "VALUES ('s-test-own','so@test.com','h','SO','ACTIVE')");
        long bank2 = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                + "VALUES (" + user2 + "," + subjectId + ",'B1','Dup')");
        assertThat(bank2).isPositive();
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
