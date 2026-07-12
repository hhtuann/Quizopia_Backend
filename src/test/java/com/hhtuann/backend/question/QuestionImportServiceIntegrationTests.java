package com.hhtuann.backend.question;

import com.hhtuann.backend.academic.domain.model.GradeLevel;
import com.hhtuann.backend.academic.domain.model.School;
import com.hhtuann.backend.academic.domain.model.Subject;
import com.hhtuann.backend.academic.domain.model.TeacherProfile;
import com.hhtuann.backend.academic.repository.GradeLevelRepository;
import com.hhtuann.backend.academic.repository.SchoolRepository;
import com.hhtuann.backend.academic.repository.SubjectRepository;
import com.hhtuann.backend.academic.repository.TeacherProfileRepository;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.question.application.QuestionImportService;
import com.hhtuann.backend.question.domain.model.Question;
import com.hhtuann.backend.question.domain.model.QuestionBank;
import com.hhtuann.backend.question.domain.model.QuestionBankStatus;
import com.hhtuann.backend.question.domain.model.QuestionStatus;
import com.hhtuann.backend.question.domain.model.QuestionType;
import com.hhtuann.backend.question.domain.model.QuestionVersion;
import com.hhtuann.backend.question.dto.ImportResponse;
import com.hhtuann.backend.question.dto.ImportResult;
import com.hhtuann.backend.question.dto.RowError;
import com.hhtuann.backend.question.exception.QuestionErrorCode;
import com.hhtuann.backend.question.exception.QuestionException;
import com.hhtuann.backend.question.importer.ExcelQuestionParser;
import com.hhtuann.backend.question.repository.QuestionBankRepository;
import com.hhtuann.backend.question.repository.QuestionOptionRepository;
import com.hhtuann.backend.question.repository.QuestionRepository;
import com.hhtuann.backend.question.repository.QuestionVersionRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link QuestionImportService} against a real
 * PostgreSQL instance via Testcontainers. Each test parses an in-memory POI
 * workbook with {@link ExcelQuestionParser}, then calls the service and reads
 * the persisted state back from the database.
 *
 * <p>Authorization is tested at the service layer (no MockMvc): active
 * TEACHER role -> QUESTION_CREATE permission -> TeacherProfile -> bank
 * ownership + school scope + ACTIVE state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionImportServiceIntegrationTests {

    @Autowired private QuestionImportService importService;
    @Autowired private ExcelQuestionParser parser;
    @Autowired private UserRepository userRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private GradeLevelRepository glRepo;
    @Autowired private SubjectRepository subjectRepo;
    @Autowired private TeacherProfileRepository teacherRepo;
    @Autowired private QuestionBankRepository bankRepo;
    @Autowired private QuestionRepository questionRepo;
    @Autowired private QuestionVersionRepository versionRepo;
    @Autowired private QuestionOptionRepository optionRepo;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private Long teacherUserId;
    private Long teacherProfileId;
    private Long schoolId;
    private Long subjectId;
    private Long bankId;

    // 0-based column indexes (must match the parser's 9-column header order).
    // Removed columns (question_code, default_points, rounding_instruction)
    // are -1 so legacy set(...) calls become no-ops (see the set helper).
    private static final int C_CODE = -1;
    private static final int C_TYPE = 0;
    private static final int C_CONTENT = 1;
    private static final int C_POINTS = -1;
    private static final int C_DIFFICULTY = 2;
    private static final int C_OPT_A = 3;
    private static final int C_OPT_B = 4;
    private static final int C_OPT_C = 5;
    private static final int C_OPT_D = 6;
    private static final int C_CORRECT = 7;
    private static final int C_EXPL = 8;
    private static final int C_ROUNDING = -1;

    @BeforeEach
    void setUp() {
        User teacher = userRepo.saveAndFlush(
                new User("import-teacher", "import-teacher@test.com", "hash", "Import Teacher"));
        teacherUserId = teacher.getId();
        // TEACHER role carries QUESTION_CREATE via the V3 seed (role_permissions).
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        School school = schoolRepo.saveAndFlush(new School("IMP-SCH", "Import School"));
        schoolId = school.getId();
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL-IMP", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(
                new Subject(schoolId, gl.getId(), "SUB-IMP", "Subject"));
        subjectId = subject.getId();

        TeacherProfile tp = teacherRepo.saveAndFlush(
                new TeacherProfile(teacherUserId, schoolId, "TC-IMP"));
        teacherProfileId = tp.getId();

        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "IMP-BANK", "Import Bank"));
        bankId = bank.getId();
    }

    /**
     * Cleans up any committed rows left by NOT_SUPPORTED test methods (H1, M1).
     * Transactional test methods roll back automatically, but methods annotated
     * {@code @Transactional(propagation = NOT_SUPPORTED)} commit their setup,
     * so this runs outside a transaction to delete test-created rows and keep
     * the DB pristine between tests.
     */
    @AfterEach
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cleanupCommittedTestData() {
        // Child-first dependency order; all test data uses well-known prefixes.
        jdbc.update("DELETE FROM question_options");
        jdbc.update("DELETE FROM question_versions");
        jdbc.update("DELETE FROM questions");
        jdbc.update("DELETE FROM question_banks WHERE code LIKE 'IMP-BANK' OR code LIKE 'TX-FAIL-BANK' OR code LIKE 'RACE-BANK' OR code LIKE 'OTHER-BANK'");
        jdbc.update("DELETE FROM teacher_profiles WHERE teacher_code IN ('TC-IMP','TC-OTHER','TC-B','TX-FAIL-TP','RACE-TP')");
        jdbc.update("DELETE FROM subjects WHERE code IN ('SUB-IMP','SUB-B','TX-FAIL-SUB','RACE-SUB')");
        jdbc.update("DELETE FROM grade_levels WHERE code IN ('GL-IMP','GL-B','TX-FAIL-GL','RACE-GL')");
        jdbc.update("DELETE FROM schools WHERE code IN ('IMP-SCH','CROSS-SCH-B','TX-FAIL-SCH','RACE-SCH')");
        jdbc.update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'SYSTEM_ADMIN') AND permission_id = (SELECT id FROM permissions WHERE code = 'QUESTION_CREATE')");
        jdbc.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE username IN ('import-teacher','auth-admin','teacher-no-profile','other-teacher','teacher-b','TX-FAIL-TEACHER','RACE-TEACHER'))");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('import-teacher','auth-admin','teacher-no-profile','other-teacher','teacher-b','TX-FAIL-TEACHER','RACE-TEACHER'))");
        jdbc.update("DELETE FROM users WHERE username IN ('import-teacher','auth-admin','teacher-no-profile','other-teacher','teacher-b','TX-FAIL-TEACHER','RACE-TEACHER')");
    }

    // ============================================================
    // Happy paths
    // ============================================================

    @Test
    void importSingleChoice() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-SC-1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "What is 2+2?");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "1");
            set(row, C_OPT_B, "2");
            set(row, C_OPT_C, "3");
            set(row, C_OPT_D, "4");
            set(row, C_CORRECT, "B");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.totalRows()).isEqualTo(1);
        assertThat(response.importedRows()).isEqualTo(1);
        assertThat(response.invalidRows()).isZero();
        assertThat(response.errors()).isEmpty();

        entityManager.clear();

        // Question exists, status DRAFT, current_version_number = 1.
        Long questionId = jdbc.queryForObject(
                "SELECT q.id FROM questions q JOIN question_versions qv ON qv.question_id = q.id "
                        + "WHERE q.question_bank_id = ? AND qv.content = 'What is 2+2?'",
                Long.class, bankId);
        assertThat(questionId).isNotNull();

        Map<String, Object> qRow = jdbc.queryForMap(
                "SELECT status, current_version_number, code FROM questions WHERE id = ?",
                questionId);
        assertThat(qRow.get("status")).isEqualTo(QuestionStatus.ACTIVE.name());
        assertThat(qRow.get("current_version_number")).isEqualTo(1);

        // Version: version_number = 1, answer_key NULL.
        Map<String, Object> versionRow = jdbc.queryForMap(
                "SELECT version_number, answer_key, question_type FROM question_versions WHERE question_id = ?",
                questionId);
        assertThat(versionRow.get("version_number")).isEqualTo(1);
        assertThat(versionRow.get("answer_key")).isNull();
        assertThat(versionRow.get("question_type")).isEqualTo(QuestionType.SINGLE_CHOICE.name());

        // 4 options at positions 0-3, exactly one is_correct (key B).
        List<Map<String, Object>> options = jdbc.queryForList(
                "SELECT option_key, position, is_correct FROM question_options "
                        + "WHERE question_version_id = (SELECT id FROM question_versions WHERE question_id = ?) "
                        + "ORDER BY position",
                questionId);
        assertThat(options).hasSize(4);
        assertThat(options).extracting(o -> o.get("position"))
                .containsExactly(0, 1, 2, 3);
        assertThat(options).extracting(o -> o.get("option_key"))
                .containsExactly("A", "B", "C", "D");
        assertThat(options).filteredOn(o -> Boolean.TRUE.equals(o.get("is_correct")))
                .singleElement()
                .extracting(o -> o.get("option_key"))
                .isEqualTo("B");
    }

    @Test
    void importMultipleChoice() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-MC-1");
            set(row, C_TYPE, "MULTIPLE_CHOICE");
            set(row, C_CONTENT, "Even numbers");
            set(row, C_POINTS, "2");
            set(row, C_DIFFICULTY, "MEDIUM");
            set(row, C_OPT_A, "2");
            set(row, C_OPT_B, "3");
            set(row, C_OPT_C, "4");
            set(row, C_OPT_D, "5");
            set(row, C_CORRECT, "AC");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isEqualTo(1);

        entityManager.clear();

        Long questionId = questionIdByCode(bankId, "Q-MC-1");
        Map<String, Object> versionRow = jdbc.queryForMap(
                "SELECT answer_key FROM question_versions WHERE question_id = ?", questionId);
        assertThat(versionRow.get("answer_key")).isNull();

        List<Map<String, Object>> options = jdbc.queryForList(
                "SELECT option_key, is_correct FROM question_options "
                        + "WHERE question_version_id = (SELECT id FROM question_versions WHERE question_id = ?)",
                questionId);
        assertThat(options).hasSize(4);
        assertThat(options).filteredOn(o -> Boolean.TRUE.equals(o.get("is_correct"))).hasSizeGreaterThanOrEqualTo(2);
        assertThat(options).filteredOn(o -> Boolean.TRUE.equals(o.get("is_correct")))
                .extracting(o -> o.get("option_key"))
                .containsExactlyInAnyOrder("A", "C");
    }

    @Test
    void importTrueFalseMatrix() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-TF-1");
            set(row, C_TYPE, "TRUE_FALSE_MATRIX");
            set(row, C_CONTENT, "Statements");
            set(row, C_POINTS, "3");
            set(row, C_DIFFICULTY, "HARD");
            // The 4 options ARE the 4 statements; correct_answers = T/F for A-D.
            set(row, C_OPT_A, "Sun is a star");
            set(row, C_OPT_B, "Water boils at 50C");
            set(row, C_OPT_C, "Iron is heavy");
            set(row, C_OPT_D, "Sound is faster than light");
            set(row, C_CORRECT, "TFTF");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isEqualTo(1);

        entityManager.clear();

        Long questionId = questionIdByCode(bankId, "Q-TF-1");
        Map<String, Object> versionRow = jdbc.queryForMap(
                "SELECT answer_key FROM question_versions WHERE question_id = ?", questionId);
        assertThat(versionRow.get("answer_key")).isNull();

        List<Map<String, Object>> options = jdbc.queryForList(
                "SELECT option_key, is_correct FROM question_options "
                        + "WHERE question_version_id = (SELECT id FROM question_versions WHERE question_id = ?) "
                        + "ORDER BY option_key",
                questionId);
        assertThat(options).hasSize(4);
        assertThat(options).extracting(o -> o.get("option_key"))
                .containsExactly("A", "B", "C", "D");
        // isCorrect matches each boolean: A=true, B=false, C=true, D=false
        assertThat(options.get(0).get("is_correct")).isEqualTo(Boolean.TRUE);
        assertThat(options.get(1).get("is_correct")).isEqualTo(Boolean.FALSE);
        assertThat(options.get(2).get("is_correct")).isEqualTo(Boolean.TRUE);
        assertThat(options.get(3).get("is_correct")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void importNumericFill_dot() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-NF-1");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "5/2");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2.50");
            set(row, C_ROUNDING, "2dp");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isEqualTo(1);

        entityManager.clear();

        Long questionId = questionIdByCode(bankId, "Q-NF-1");

        // 0 options for NUMERIC_FILL.
        Integer optionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_options "
                        + "WHERE question_version_id = (SELECT id FROM question_versions WHERE question_id = ?)",
                Integer.class, questionId);
        assertThat(optionCount).isZero();

        // answer_key not null; expectedAnswer JSON string "2.50"; requiredInputLength JSON number 4;
        // roundingInstruction non-blank.
        String answerKeyPresent = jdbc.queryForObject(
                "SELECT answer_key::text FROM question_versions WHERE question_id = ?",
                String.class, questionId);
        assertThat(answerKeyPresent).isNotNull();

        String expectedAnswer = jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM question_versions WHERE question_id = ?",
                String.class, questionId);
        assertThat(expectedAnswer).isEqualTo("2.50");
    }

    @Test
    void importNumericFill_comma() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-NF-2");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "2,50");
            set(row, C_ROUNDING, "2dp");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isEqualTo(1);

        entityManager.clear();

        Long questionId = questionIdByCode(bankId, "Q-NF-2");
        String expectedAnswer = jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM question_versions WHERE question_id = ?",
                String.class, questionId);
        // comma normalized to dot, trailing zero preserved
        assertThat(expectedAnswer).isEqualTo("2.50");
    }

    @Test
    void importNumericFill_leadingZero() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "Q-NF-3");
            set(row, C_TYPE, "NUMERIC_FILL");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            setNumericString(row, "02.5");
            set(row, C_ROUNDING, "2dp");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isEqualTo(1);

        entityManager.clear();

        Long questionId = questionIdByCode(bankId, "Q-NF-3");
        String expectedAnswer = jdbc.queryForObject(
                "SELECT answer_key->>'expectedAnswer' FROM question_versions WHERE question_id = ?",
                String.class, questionId);
        // leading zero preserved
        assertThat(expectedAnswer).isEqualTo("02.5");
    }

    // ============================================================
    // Mixed + edge cases
    // ============================================================

    @Test
    void importMixedFile_partialSuccess() throws Exception {
        ImportResult parse = parse(workbook(
                row -> { // valid SINGLE_CHOICE
                    set(row, C_CODE, "MX-1");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "single?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> { // valid NUMERIC_FILL
                    set(row, C_CODE, "MX-2");
                    set(row, C_TYPE, "NUMERIC_FILL");
                    set(row, C_CONTENT, "numeric?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    setNumericString(row, "3.14");
                    set(row, C_ROUNDING, "2dp");
                },
                row -> { // invalid: blank content
                    set(row, C_CODE, "MX-3");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    // content blank
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.totalRows()).isEqualTo(3);
        assertThat(response.importedRows()).isEqualTo(2);
        assertThat(response.invalidRows()).isEqualTo(1);
        assertThat(response.errors()).hasSize(1);
        RowError err = response.errors().get(0);
        // invalid row is sheet row index 3 (0-based) -> display row number 4
        assertThat(err.rowNumber()).isEqualTo(4);
        assertThat(err.field()).isEqualTo("content");

        entityManager.clear();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?",
                Integer.class, bankId);
        assertThat(count).isEqualTo(2);
    }

    @org.junit.jupiter.api.Disabled("question codes are auto-generated; import code-collision detection removed")
    @Test
    void importExistingDuplicate_caseInsensitive() throws Exception {
        // Pre-insert a question with code "Q-001" in the bank.
        QuestionVersion existingVersion = preInsertQuestion("Q-001",
                QuestionType.SINGLE_CHOICE, "existing content");

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "q-001"); // same code, different case
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "new content");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        // Duplicate row not imported.
        assertThat(response.importedRows()).isZero();
        assertThat(response.errors()).anyMatch(e -> e.code()
                .equals(QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE.name()));

        entityManager.clear();

        // Original question NOT overwritten: still 1 question, version_number still 1,
        // content unchanged, options unchanged.
        Integer questionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?",
                Integer.class, bankId);
        assertThat(questionCount).isEqualTo(1);

        Integer versionNumber = jdbc.queryForObject(
                "SELECT version_number FROM question_versions WHERE question_id = ?",
                Integer.class, existingVersion.getQuestionId());
        assertThat(versionNumber).isEqualTo(1);

        String content = jdbc.queryForObject(
                "SELECT content FROM question_versions WHERE question_id = ?",
                String.class, existingVersion.getQuestionId());
        assertThat(content).isEqualTo("existing content");
    }

    @Test
    void importAllInvalid() throws Exception {
        ImportResult parse = parse(workbook(
                row -> { // blank content
                    set(row, C_CODE, "BAD-1");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> { // blank content
                    set(row, C_CODE, "BAD-2");
                    set(row, C_TYPE, "MULTIPLE_CHOICE");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A,B");
                }));

        ImportResponse response = importService.importParsedQuestions(teacherUserId, bankId, parse);

        assertThat(response.importedRows()).isZero();
        assertThat(response.errors()).isNotEmpty();
        assertThat(response.invalidRows()).isEqualTo(2);

        entityManager.clear();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?",
                Integer.class, bankId);
        assertThat(count).isZero();
    }

    // ============================================================
    // Authorization tests (service layer, not MockMvc)
    // ============================================================

    @Test
    void missingPermission_returns403() throws Exception {
        revokePermission("QUESTION_CREATE");
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        assertThatThrownBy(() -> importService.importParsedQuestions(teacherUserId, bankId, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
    }

    @Test
    void nonTeacherWithPermission_returns403() throws Exception {
        User admin = userRepo.saveAndFlush(
                new User("auth-admin", "auth-admin@test.com", "hash", "Auth Admin"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'SYSTEM_ADMIN'", admin.getId());
        // Grant QUESTION_CREATE to SYSTEM_ADMIN so the permission check passes,
        // but the active-TEACHER-role check must still deny.
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                + "SELECT r.id, p.id FROM roles r, permissions p "
                + "WHERE r.code = 'SYSTEM_ADMIN' AND p.code = 'QUESTION_CREATE'");

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-2");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        assertThatThrownBy(() -> importService.importParsedQuestions(admin.getId(), bankId, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
    }

    @Test
    void teacherWithoutProfile_returns404() throws Exception {
        User teacherNoProfile = userRepo.saveAndFlush(
                new User("teacher-no-profile", "teacher-no-profile@test.com", "hash", "Teacher No TP"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherNoProfile.getId());

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-3");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        assertThatThrownBy(() -> importService.importParsedQuestions(
                teacherNoProfile.getId(), bankId, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_TEACHER_PROFILE_NOT_FOUND);
    }

    @Test
    void crossOwner_returns403() throws Exception {
        // Second teacher + bank owned by them.
        User other = userRepo.saveAndFlush(
                new User("other-teacher", "other-teacher@test.com", "hash", "Other Teacher"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());
        TeacherProfile otherTp = teacherRepo.saveAndFlush(
                new TeacherProfile(other.getId(), schoolId, "TC-OTHER"));
        QuestionBank otherBank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, otherTp.getId(), "OTHER-BANK", "Other Bank"));

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-4");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        // First teacher (teacherUserId) importing into second teacher's bank.
        assertThatThrownBy(() -> importService.importParsedQuestions(
                teacherUserId, otherBank.getId(), parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
    }

    @Test
    void bankNotFound_returns404() throws Exception {
        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-5");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        assertThatThrownBy(() -> importService.importParsedQuestions(
                teacherUserId, 999999L, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_NOT_FOUND);
    }

    @Test
    void archivedBank_returns403() throws Exception {
        QuestionBank bank = bankRepo.findById(bankId).orElseThrow();
        bank.setStatus(QuestionBankStatus.ARCHIVED);
        bankRepo.saveAndFlush(bank);

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "AUTH-6");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        assertThatThrownBy(() -> importService.importParsedQuestions(teacherUserId, bankId, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
    }

    // ============================================================
    // Transactional integrity tests (H1, M1)
    // Suspend the class-level @Transactional so the service runs in its own
    // committed/rolled-back transaction and DB assertions see the final state.
    // ============================================================

    /**
     * H1 — All-or-nothing rollback on a DB CHECK violation mid-batch.
     *
     * <p>A test-only CHECK constraint rejects code 'TX-FAIL'. Both rows pass
     * the parser and DB-duplicate pre-check, so they reach persistence. When
     * the second row is flushed, the CHECK fires, the service rethrows the
     * {@link DataIntegrityViolationException} (constraint name is not
     * {@code uk_questions_bank_code_ci}), and the whole transaction rolls back,
     * leaving zero questions/versions/options.
     */
    @org.junit.jupiter.api.Disabled("question codes are auto-generated; import code-collision detection removed")
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void checkConstraintViolation_rollsBackEntireBatch() throws Exception {
        Long localTeacherUserId = insertPrerequisites("TX-FAIL-TEACHER", "TX-FAIL-SCH",
                "TX-FAIL-GL", "TX-FAIL-SUB", "TX-FAIL-TP", "TX-FAIL-BANK");
        Long localBankId = jdbc.queryForObject(
                "SELECT id FROM question_banks WHERE code = 'TX-FAIL-BANK'", Long.class);

        jdbc.update("ALTER TABLE questions ADD CONSTRAINT chk_test_reject_tx_fail "
                + "CHECK (code <> 'TX-FAIL')");

        ImportResult parseResult = parse(workbook(
                row -> {
                    set(row, C_CODE, "TX-OK");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "ok?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> {
                    set(row, C_CODE, "TX-FAIL");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "fail?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                }));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    importService.importParsedQuestions(localTeacherUserId, localBankId, parseResult)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // All-or-nothing: neither TX-OK nor TX-FAIL persisted.
            Integer questionCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM questions WHERE question_bank_id = ? "
                            + "AND LOWER(code) IN ('tx-ok','tx-fail')",
                    Integer.class, localBankId);
            assertThat(questionCount).isZero();

            Integer versionCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM question_versions qv "
                            + "JOIN questions q ON qv.question_id = q.id "
                            + "WHERE q.question_bank_id = ? "
                            + "AND LOWER(q.code) IN ('tx-ok','tx-fail')",
                    Integer.class, localBankId);
            assertThat(versionCount).isZero();

            Integer optionCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM question_options qo "
                            + "JOIN question_versions qv ON qo.question_version_id = qv.id "
                            + "JOIN questions q ON qv.question_id = q.id "
                            + "WHERE q.question_bank_id = ? "
                            + "AND LOWER(q.code) IN ('tx-ok','tx-fail')",
                    Integer.class, localBankId);
            assertThat(optionCount).isZero();
        } finally {
            jdbc.update("ALTER TABLE questions DROP CONSTRAINT IF EXISTS chk_test_reject_tx_fail");
        }
    }

    /**
     * M1 — Late unique-conflict at flush (trigger-simulated race).
     *
     * <p>A BEFORE INSERT trigger on {@code questions} inserts a 'RACE-DUP' row
     * whenever 'RACE-FIRST' is inserted. The batch pre-check
     * ({@code findLowerCodesByBankId}) returns empty because neither code
     * exists yet. When RACE-FIRST is flushed the trigger inserts RACE-DUP; when
     * the batch's own RACE-DUP row is flushed, {@code uk_questions_bank_code_ci}
     * fires, the service maps it to QUESTION_IMPORT_DUPLICATE_CODE, and the
     * transaction rolls back (zero questions persisted).
     */
    @org.junit.jupiter.api.Disabled("question codes are auto-generated; import code-collision detection removed")
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateUniqueConflictAtFlush_mapsToDuplicateAndRollsBack() throws Exception {
        Long localTeacherUserId = insertPrerequisites("RACE-TEACHER", "RACE-SCH",
                "RACE-GL", "RACE-SUB", "RACE-TP", "RACE-BANK");
        Long localBankId = jdbc.queryForObject(
                "SELECT id FROM question_banks WHERE code = 'RACE-BANK'", Long.class);

        jdbc.update("CREATE OR REPLACE FUNCTION fn_test_race_dup() RETURNS TRIGGER AS $$ "
                + "BEGIN "
                + "IF NEW.code = 'RACE-FIRST' THEN "
                + "INSERT INTO questions (question_bank_id, code, current_version_number, status, created_by) "
                + "VALUES (NEW.question_bank_id, 'RACE-DUP', 1, 'DRAFT', NEW.created_by); "
                + "END IF; "
                + "RETURN NEW; "
                + "END; $$ LANGUAGE plpgsql");
        jdbc.update("CREATE TRIGGER trg_test_race_dup BEFORE INSERT ON questions "
                + "FOR EACH ROW EXECUTE FUNCTION fn_test_race_dup()");

        ImportResult parseResult = parse(workbook(
                row -> {
                    set(row, C_CODE, "RACE-FIRST");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "first?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> {
                    set(row, C_CODE, "RACE-DUP");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "dup?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                }));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    importService.importParsedQuestions(localTeacherUserId, localBankId, parseResult)))
                    .isInstanceOf(QuestionException.class)
                    .extracting(e -> ((QuestionException) e).getErrorCode())
                    .isEqualTo(QuestionErrorCode.QUESTION_IMPORT_DUPLICATE_CODE);

            Integer raceCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM questions WHERE question_bank_id = ? "
                            + "AND LOWER(code) IN ('race-first','race-dup')",
                    Integer.class, localBankId);
            assertThat(raceCount).isZero();
        } finally {
            jdbc.update("DROP TRIGGER IF EXISTS trg_test_race_dup ON questions");
            jdbc.update("DROP FUNCTION IF EXISTS fn_test_race_dup()");
        }
    }

    // ============================================================
    // Cross-school authorization (M2)
    // ============================================================

    /**
     * M2 — Teacher from school B is denied importing into a bank owned by a
     * teacher in school A (schoolId mismatch on the bank).
     */
    @Test
    void crossSchoolTeacher_returns403() throws Exception {
        School schoolB = schoolRepo.saveAndFlush(new School("CROSS-SCH-B", "School B"));
        Long schoolBId = schoolB.getId();
        GradeLevel glB = glRepo.saveAndFlush(
                new GradeLevel(schoolBId, "GL-B", "Grade B"));
        subjectRepo.saveAndFlush(
                new Subject(schoolBId, glB.getId(), "SUB-B", "Subject B"));

        User teacherB = userRepo.saveAndFlush(
                new User("teacher-b", "teacher-b@test.com", "hash", "Teacher B"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherB.getId());
        teacherRepo.saveAndFlush(new TeacherProfile(teacherB.getId(), schoolBId, "TC-B"));

        assertThat(schoolBId).isNotEqualTo(schoolId);

        ImportResult parse = parse(workbook(row -> {
            set(row, C_CODE, "CROSS-1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "x");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        }));

        // bankId belongs to school A (from @BeforeEach), teacherB is in school B.
        assertThatThrownBy(() -> importService.importParsedQuestions(
                teacherB.getId(), bankId, parse))
                .isInstanceOf(QuestionException.class)
                .extracting(e -> ((QuestionException) e).getErrorCode())
                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Inserts a full prerequisite chain (user + TEACHER role, school,
     * grade_level, subject, teacher_profile, ACTIVE bank) via jdbc, so tests
     * that suspend the class-level transaction have committed setup data to
     * work against. Returns the new teacher user id.
     */
    private Long insertPrerequisites(String username, String schoolCode, String glCode,
                                     String subjectCode, String teacherCode, String bankCode) {
        jdbc.update("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES (?, ?, 'hash', ?) "
                + "ON CONFLICT DO NOTHING",
                username, username + "@test.com", username);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", userId);

        jdbc.update("INSERT INTO schools (code, name) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", schoolCode, schoolCode);
        Long sId = jdbc.queryForObject(
                "SELECT id FROM schools WHERE code = ?", Long.class, schoolCode);

        jdbc.update("INSERT INTO grade_levels (school_id, code, name) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", sId, glCode, glCode);
        Long glId = jdbc.queryForObject(
                "SELECT id FROM grade_levels WHERE school_id = ? AND code = ?",
                Long.class, sId, glCode);

        jdbc.update("INSERT INTO subjects (school_id, grade_level_id, code, name) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", sId, glId, subjectCode, subjectCode);

        jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", userId, sId, teacherCode);
        Long tpId = jdbc.queryForObject(
                "SELECT id FROM teacher_profiles WHERE user_id = ?", Long.class, userId);

        Long subjId = jdbc.queryForObject(
                "SELECT id FROM subjects WHERE school_id = ? AND code = ?",
                Long.class, sId, subjectCode);
        jdbc.update("INSERT INTO question_banks "
                + "(school_id, subject_id, owner_teacher_id, code, name, status) "
                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE') ON CONFLICT DO NOTHING",
                sId, subjId, tpId, bankCode, bankCode);

        return userId;
    }

    /** Pre-inserts a question (with one version + no options) into the bank. */
    private QuestionVersion preInsertQuestion(String code, QuestionType type, String content) {
        Question q = questionRepo.saveAndFlush(new Question(bankId, code, teacherUserId));
        QuestionVersion v = versionRepo.saveAndFlush(new QuestionVersion(
                q.getId(), 1, type, content, teacherUserId, BigDecimal.ONE));
        return v;
    }

    private Long questionIdByCode(Long bankId, String code) {
        // Question codes are now auto-generated; each happy-path test imports exactly one
        // question into a fresh bank, so look it up by bank (code param is ignored).
        return jdbc.queryForObject(
                "SELECT id FROM questions WHERE question_bank_id = ?",
                Long.class, bankId);
    }

    private void revokePermission(String permCode) {
        jdbc.update("DELETE FROM role_permissions "
                        + "WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER') "
                        + "AND permission_id = (SELECT id FROM permissions WHERE code = ?)",
                permCode);
    }

    private ImportResult parse(Workbook wb) throws IOException {
        return parser.parse(new ByteArrayInputStream(toBytes(wb)));
    }

    private byte[] toBytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    @FunctionalInterface
    private interface RowPopulator {
        void populate(Row row);
    }

    /** Builds a workbook with a Questions sheet + header, then one data row. */
    private Workbook workbook(RowPopulator row1) throws IOException {
        return workbook(row1, null, null);
    }

    private Workbook workbook(RowPopulator row1, RowPopulator row2) throws IOException {
        return workbook(row1, row2, null);
    }

    private Workbook workbook(RowPopulator row1, RowPopulator row2, RowPopulator row3)
            throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Questions");
        Row header = sheet.createRow(0);
        for (int i = 0; i < ExcelQuestionParser.EXPECTED_HEADERS.size(); i++) {
            header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
        }
        RowPopulator[] pops = {row1, row2, row3};
        for (int i = 0; i < pops.length; i++) {
            if (pops[i] != null) {
                pops[i].populate(sheet.createRow(i + 1));
            }
        }
        return wb;
    }

    private static void set(Row row, int col, String value) {
        if (value == null || col < 0) {
            return; // col < 0 = removed column → no-op
        }
        row.createCell(col).setCellValue(value);
    }

    /** Sets the numeric_answer cell as a STRING (text-formatted) value. */
    private static void setNumericString(Row row, String value) {
        // For NUMERIC_FILL the answer lives in the correct_answers column as text.
        Cell cell = row.createCell(C_CORRECT);
        cell.setCellValue(value); // STRING cell type
    }
}
