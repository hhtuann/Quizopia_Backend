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
import com.hhtuann.backend.question.domain.model.QuestionBank;
import com.hhtuann.backend.question.domain.model.QuestionBankStatus;
import com.hhtuann.backend.question.importer.ExcelQuestionParser;
import com.hhtuann.backend.question.repository.QuestionBankRepository;
import com.hhtuann.backend.question.repository.QuestionOptionRepository;
import com.hhtuann.backend.question.repository.QuestionRepository;
import com.hhtuann.backend.question.repository.QuestionVersionRepository;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code POST /api/question-banks/{bankId}/questions/import}
 * (Batch B2.2) using MockMvc + Testcontainers. Exercises the full HTTP stack:
 * security filter chain, scoped exception handler, controller file-metadata
 * validation, parser, and transactional persistence service.
 *
 * <p>Key guarantee under test: the preflight authorization runs BEFORE the
 * workbook is parsed — an unauthorized caller never pays the parse cost and
 * receives the auth status (403/404), not a file error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionImportApiIntegrationTests {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private GradeLevelRepository glRepo;
    @Autowired private SubjectRepository subjectRepo;
    @Autowired private TeacherProfileRepository teacherRepo;
    @Autowired private QuestionBankRepository bankRepo;
    @Autowired private QuestionRepository questionRepo;
    @Autowired private QuestionVersionRepository versionRepo;
    @Autowired private QuestionOptionRepository optionRepo;
    @Autowired private JdbcTemplate jdbc;

    private Long teacherUserId;
    private Long teacherProfileId;
    private Long schoolId;
    private Long subjectId;
    private Long bankId;

    // 0-based column indexes (must match the parser's header order).
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
    private static final int C_NUMERIC = 16;
    private static final int C_ROUNDING = -1;

    @BeforeEach
    void setUp() {
        User teacher = userRepo.saveAndFlush(
                new User("api-import-teacher", "apiit@test.com", "hash", "API Import Teacher"));
        teacherUserId = teacher.getId();
        // TEACHER role carries QUESTION_CREATE via the V3 seed (role_permissions).
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        School school = schoolRepo.saveAndFlush(new School("API-IMP-SCH", "API Import School"));
        schoolId = school.getId();
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL-IMP", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(
                new Subject(schoolId, gl.getId(), "SUB-IMP", "Subject"));
        subjectId = subject.getId();

        TeacherProfile tp = teacherRepo.saveAndFlush(
                new TeacherProfile(teacherUserId, schoolId, "TC-API-IMP"));
        teacherProfileId = tp.getId();

        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "API-IMP-BANK", "API Import Bank"));
        bankId = bank.getId();
    }

    @AfterEach
    void cleanupCommittedTestData() {
        // @Transactional rolls back most tests; this guards against any
        // committed setup (non-transactional collaborators) and keeps the DB
        // pristine. All test data uses well-known prefixes.
        jdbc.update("DELETE FROM question_options");
        jdbc.update("DELETE FROM question_versions");
        jdbc.update("DELETE FROM questions");
        jdbc.update("DELETE FROM question_banks WHERE code IN ('API-IMP-BANK','OTHER-API-BANK','ARCH-API-BANK')");
        jdbc.update("DELETE FROM teacher_profiles WHERE teacher_code IN ('TC-API-IMP','TC-API-OTHER','TC-API-NOTP')");
        jdbc.update("DELETE FROM subjects WHERE code = 'SUB-IMP'");
        jdbc.update("DELETE FROM grade_levels WHERE code = 'GL-IMP'");
        jdbc.update("DELETE FROM schools WHERE code = 'API-IMP-SCH'");
        jdbc.update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'SYSTEM_ADMIN') AND permission_id = (SELECT id FROM permissions WHERE code = 'QUESTION_CREATE')");
        jdbc.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE username IN ('api-import-teacher','api-import-student','api-import-admin','api-import-notp','api-import-other'))");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username IN ('api-import-teacher','api-import-student','api-import-admin','api-import-notp','api-import-other'))");
        jdbc.update("DELETE FROM users WHERE username IN ('api-import-teacher','api-import-student','api-import-admin','api-import-notp','api-import-other')");
    }

    // ============================================================
    // Happy path + partial success
    // ============================================================

    @Test
    void importValidFile_returns200AndPersists() throws Exception {
        byte[] xlsx = workbookBytes(row -> {
            set(row, C_CODE, "Q-API-1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "What is 2+2?");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "1");
            set(row, C_OPT_B, "2");
            set(row, C_OPT_C, "3");
            set(row, C_OPT_D, "4");
            set(row, C_CORRECT, "B");
        });

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedRows").value(1))
                .andExpect(jsonPath("$.invalidRows").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        // Question + version + 4 options persisted.
        Integer qCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, bankId);
        assertThat(qCount).isEqualTo(1);

        Long questionId = jdbc.queryForObject(
                "SELECT id FROM questions WHERE question_bank_id = ?",
                Long.class, bankId);

        Integer vCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_versions WHERE question_id = ?",
                Integer.class, questionId);
        assertThat(vCount).isEqualTo(1);

        Integer oCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_options WHERE question_version_id = "
                        + "(SELECT id FROM question_versions WHERE question_id = ?)",
                Integer.class, questionId);
        assertThat(oCount).isEqualTo(4);
    }

    @Test
    void importMixedFile_returns200WithErrors() throws Exception {
        byte[] xlsx = workbookBytes(
                row -> { // valid SINGLE_CHOICE
                    set(row, C_CODE, "MX-A-1");
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
                row -> { // valid SINGLE_CHOICE
                    set(row, C_CODE, "MX-A-2");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    set(row, C_CONTENT, "second?");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                },
                row -> { // invalid: blank content
                    set(row, C_CODE, "MX-A-3");
                    set(row, C_TYPE, "SINGLE_CHOICE");
                    // content blank
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A");
                });

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.importedRows").value(2))
                .andExpect(jsonPath("$.invalidRows").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                // invalid row is sheet row index 3 (0-based) -> display row 4
                .andExpect(jsonPath("$.errors[0].rowNumber").value(4))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
    }

    @Test
    void importAllInvalid_returns200ImportedRowsZero() throws Exception {
        byte[] xlsx = workbookBytes(
                row -> { // blank content
                    set(row, C_CODE, "BAD-A-1");
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
                    set(row, C_CODE, "BAD-A-2");
                    set(row, C_TYPE, "MULTIPLE_CHOICE");
                    set(row, C_POINTS, "1");
                    set(row, C_DIFFICULTY, "EASY");
                    set(row, C_OPT_A, "a");
                    set(row, C_OPT_B, "b");
                    set(row, C_OPT_C, "c");
                    set(row, C_OPT_D, "d");
                    set(row, C_CORRECT, "A,B");
                });

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.invalidRows").value(2));

        Integer qCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, bankId);
        assertThat(qCount).isZero();
    }

    @org.junit.jupiter.api.Disabled("question codes are auto-generated; import code-collision detection removed")
    @Test
    void importExistingDuplicate_returns200WithError() throws Exception {
        // Pre-insert a question with code "DUP-API-1".
        var q = questionRepo.saveAndFlush(
                new com.hhtuann.backend.question.domain.model.Question(bankId, "DUP-API-1", teacherUserId));
        versionRepo.saveAndFlush(new com.hhtuann.backend.question.domain.model.QuestionVersion(
                q.getId(), 1, com.hhtuann.backend.question.domain.model.QuestionType.SINGLE_CHOICE,
                "existing", teacherUserId, java.math.BigDecimal.ONE));

        byte[] xlsx = workbookBytes(row -> {
            set(row, C_CODE, "dup-api-1"); // same code, different case
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "new");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        });

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.errors[0].code")
                        .value("QUESTION_IMPORT_DUPLICATE_CODE"));
    }

    // ============================================================
    // Authorization tests (preflight runs before parse)
    // ============================================================

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void student_returns403() throws Exception {
        User student = userRepo.saveAndFlush(
                new User("api-import-student", "apis@test.com", "hash", "API Import Student"));
        // STUDENT role has no QUESTION_CREATE permission.
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(student.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void nonTeacherWithQuestionCreate_returns403() throws Exception {
        User admin = userRepo.saveAndFlush(
                new User("api-import-admin", "apia@test.com", "hash", "API Import Admin"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'SYSTEM_ADMIN'", admin.getId());
        // Grant QUESTION_CREATE to SYSTEM_ADMIN so the permission check passes,
        // but the active-TEACHER-role check must still deny.
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                + "SELECT r.id, p.id FROM roles r, permissions p "
                + "WHERE r.code = 'SYSTEM_ADMIN' AND p.code = 'QUESTION_CREATE'");

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(admin.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void teacherRevokedQuestionCreate_returns403() throws Exception {
        revokePermission("QUESTION_CREATE");
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void teacherWithoutProfile_returns404() throws Exception {
        User teacherNoProfile = userRepo.saveAndFlush(
                new User("api-import-notp", "aintp@test.com", "hash", "API Import No TP"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherNoProfile.getId());

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(teacherNoProfile.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_TEACHER_PROFILE_NOT_FOUND"));
    }

    @Test
    void crossOwner_returns403() throws Exception {
        User other = userRepo.saveAndFlush(
                new User("api-import-other", "aio@test.com", "hash", "API Import Other"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());
        TeacherProfile otherTp = teacherRepo.saveAndFlush(
                new TeacherProfile(other.getId(), schoolId, "TC-API-OTHER"));
        QuestionBank otherBank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, otherTp.getId(), "OTHER-API-BANK", "Other API Bank"));

        // First teacher importing into second teacher's bank.
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", otherBank.getId())
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void archivedBank_returns403() throws Exception {
        QuestionBank bank = bankRepo.findById(bankId).orElseThrow();
        bank.setStatus(QuestionBankStatus.ARCHIVED);
        bankRepo.saveAndFlush(bank);

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void bankMissing_returns404() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", 999999L)
                        .file(xlsxPart(validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_NOT_FOUND"));
    }

    /**
     * Unauthorized caller uploads CORRUPT bytes — must still receive 403 (the
     * preflight auth), NOT a 400 file error. Proves the workbook is never parsed
     * when the caller is unauthorized.
     */
    @Test
    void unauthorizedSendsCorruptFile_returns403NotFileError() throws Exception {
        User student = userRepo.saveAndFlush(
                new User("api-import-student", "apis2@test.com", "hash", "API Import Student 2"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

        MockMultipartFile corrupt = new MockMultipartFile(
                "file", "not-a-workbook.xlsx", XLSX_MIME, new byte[]{0x00, 0x01, 0x02, 0x03});

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(corrupt)
                        .with(jwtFor(student.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    // ============================================================
    // File-metadata validation tests
    // ============================================================

    @Test
    void missingFilePart_returns400() throws Exception {
        // An empty multipart with NO file part.
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.xlsx", XLSX_MIME, new byte[0]);

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(empty)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void wrongContentType_returns415() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(new MockMultipartFile(
                                "file", "valid.xlsx", "application/json", validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void partContentTypeTextPlain_returns415() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(new MockMultipartFile(
                                "file", "valid.xlsx", "text/plain", validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void filenameXls_returns415() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(new MockMultipartFile(
                                "file", "workbook.xls", XLSX_MIME, validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void filenameCsv_returns415() throws Exception {
        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(new MockMultipartFile(
                                "file", "workbook.csv", XLSX_MIME, validSingleChoiceBytes()))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void corruptBytesWithXlsxName_returns400() throws Exception {
        MockMultipartFile corrupt = new MockMultipartFile(
                "file", "corrupt.xlsx", XLSX_MIME, new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00});

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(corrupt)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void missingQuestionsSheet_returns400TemplateInvalid() throws Exception {
        // A valid xlsx but the first sheet is NOT named "Questions".
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("WrongName");
            Row header = wb.getSheetAt(0).createRow(0);
            for (int i = 0; i < ExcelQuestionParser.EXPECTED_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
            }
            xlsx = toBytes(wb);
        }

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_TEMPLATE_INVALID"));
    }

    @Test
    void wrongHeader_returns400TemplateInvalid() throws Exception {
        // Correct sheet name but a wrong header (first column renamed).
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Questions");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("WRONG_HEADER");
            for (int i = 1; i < ExcelQuestionParser.EXPECTED_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(ExcelQuestionParser.EXPECTED_HEADERS.get(i));
            }
            xlsx = toBytes(wb);
        }

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(xlsxPart(xlsx))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_TEMPLATE_INVALID"));
    }

    // ============================================================
    // Size test
    // ============================================================

    /**
     * A part whose size exceeds the controller's {@code MAX_FILE_SIZE} (5 MiB)
     * is rejected with 413 by the controller's manual {@code file.getSize()}
     * check (independent of the servlet's multipart limit).
     */
    @Test
    void fileOverMaxSize_returns413() throws Exception {
        byte[] tooLarge = new byte[(int) (com.hhtuann.backend.question.api.QuestionImportController.MAX_FILE_SIZE + 1)];
        MockMultipartFile big = new MockMultipartFile(
                "file", "huge.xlsx", XLSX_MIME, tooLarge);

        mockMvc.perform(multipart("/api/question-banks/{bankId}/questions/import", bankId)
                        .file(big)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void revokePermission(String permCode) {
        jdbc.update("DELETE FROM role_permissions "
                        + "WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER') "
                        + "AND permission_id = (SELECT id FROM permissions WHERE code = ?)",
                permCode);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
    }

    private MockMultipartFile xlsxPart(byte[] content) {
        return new MockMultipartFile("file", "upload.xlsx", XLSX_MIME, content);
    }

    private byte[] validSingleChoiceBytes() throws IOException {
        return workbookBytes(row -> {
            set(row, C_CODE, "Q-VALID-1");
            set(row, C_TYPE, "SINGLE_CHOICE");
            set(row, C_CONTENT, "ok?");
            set(row, C_POINTS, "1");
            set(row, C_DIFFICULTY, "EASY");
            set(row, C_OPT_A, "a");
            set(row, C_OPT_B, "b");
            set(row, C_OPT_C, "c");
            set(row, C_OPT_D, "d");
            set(row, C_CORRECT, "A");
        });
    }

    private byte[] workbookBytes(RowPopulator row1) throws IOException {
        return workbookBytes(row1, null, null);
    }

    private byte[] workbookBytes(RowPopulator row1, RowPopulator row2) throws IOException {
        return workbookBytes(row1, row2, null);
    }

    private byte[] workbookBytes(RowPopulator row1, RowPopulator row2, RowPopulator row3)
            throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
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
            return toBytes(wb);
        }
    }

    private static byte[] toBytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static void set(Row row, int col, String value) {
        if (value == null || col < 0) {
            return; // col < 0 = removed column → no-op
        }
        row.createCell(col).setCellValue(value);
    }

    @FunctionalInterface
    private interface RowPopulator {
        void populate(Row row);
    }
}
