package com.quizopia.backend.question;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.application.QuestionImportService;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.domain.model.QuestionBankStatus;
import com.quizopia.backend.question.domain.model.QuestionStatus;
import com.quizopia.backend.question.domain.model.QuestionType;
import com.quizopia.backend.question.dto.ImportResponse;
import com.quizopia.backend.question.dto.ImportResult;
import com.quizopia.backend.question.dto.RowError;
import com.quizopia.backend.question.exception.QuestionErrorCode;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.question.importer.ExcelQuestionParser;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
 * Authorization is tested at the service layer (no MockMvc): active
 * TEACHER role -> QUESTION_CREATE permission -> bank creator equality ->
 * ACTIVE state (P2-C guard, user-centric ownership model).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionImportServiceIntegrationTests {

        @Autowired
        private QuestionImportService importService;
        @Autowired
        private QuestionBankService bankService;
        @Autowired
        private ExcelQuestionParser parser;
        @Autowired
        private UserRepository userRepo;
        @Autowired
        private GradeLevelRepository glRepo;
        @Autowired
        private SubjectRepository subjectRepo;
        @Autowired
        private QuestionBankRepository bankRepo;
        @Autowired
        private EntityManager entityManager;
        @Autowired
        private JdbcTemplate jdbc;

        private Long teacherUserId;
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
        private static final int C_ROUNDING = -1;

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("import-teacher", "import-teacher@test.com", "hash", "Import Teacher"));
                teacherUserId = teacher.getId();
                // TEACHER role carries QUESTION_CREATE via the V3 seed (role_permissions).
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel("GL-IMP", "Grade"));
                Subject subject = subjectRepo.saveAndFlush(
                                new Subject(gl.getId(), "SUB-IMP", "Subject"));
                subjectId = subject.getId();

                QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                                teacherUserId, subjectId, "IMP-BANK", "Import Bank"));
                bankId = bank.getId();
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
                assertThat(options).filteredOn(o -> Boolean.TRUE.equals(o.get("is_correct")))
                                .hasSizeGreaterThanOrEqualTo(2);
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

                // answer_key not null; expectedAnswer JSON string "2.50"; requiredInputLength
                // JSON number 4;
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
                User permHolder = userRepo.saveAndFlush(
                                new User("auth-perm", "auth-perm@test.com", "hash", "Auth Perm Holder"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", permHolder.getId());
                // Grant QUESTION_CREATE to STUDENT so the permission check passes,
                // but the active-TEACHER-role check must still deny.
                jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                                + "SELECT r.id, p.id FROM roles r, permissions p "
                                + "WHERE r.code = 'STUDENT' AND p.code = 'QUESTION_CREATE'");

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

                assertThatThrownBy(() -> importService.importParsedQuestions(permHolder.getId(), bankId, parse))
                                .isInstanceOf(QuestionException.class)
                                .extracting(e -> ((QuestionException) e).getErrorCode())
                                .isEqualTo(QuestionErrorCode.QUESTION_BANK_ACCESS_DENIED);
        }

        @Test
        void teacherImportIntoOwnSubjectlessBank_importsSuccessfully() throws Exception {
                // Any TEACHER owns the banks they create (subject metadata optional);
                // ownership is the import boundary.
                User teacher = userRepo.saveAndFlush(
                                new User("own-bank-teacher", "own-bank-teacher@test.com", "hash", "Own Bank Teacher"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacher.getId());

                Long ownBankId = bankService.createBank(teacher.getId(),
                                new com.quizopia.backend.question.dto.CreateQuestionBankRequest(
                                                "IMP-PERSONAL-BANK", "Personal Bank", null, null))
                                .id();

                ImportResult parse = parse(workbook(row -> {
                        set(row, C_CODE, "AUTH-3P");
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

                ImportResponse response = importService.importParsedQuestions(
                                teacher.getId(), ownBankId, parse);
                assertThat(response.importedRows()).isEqualTo(1);
        }

        @Test
        void crossOwner_returns403() throws Exception {
                // Second teacher + bank owned by them.
                User other = userRepo.saveAndFlush(
                                new User("other-teacher", "other-teacher@test.com", "hash", "Other Teacher"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());
                QuestionBank otherBank = bankRepo.saveAndFlush(new QuestionBank(
                                other.getId(), subjectId, "OTHER-BANK", "Other Bank"));

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


        private Long questionIdByCode(Long bankId, String code) {
                // Question codes are now auto-generated; each happy-path test imports exactly
                // one
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
                RowPopulator[] pops = { row1, row2, row3 };
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
