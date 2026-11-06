package com.quizopia.backend.question;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.application.MarkdownQuestionImportService;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for
 * {@code POST /api/question-banks/{bankId}/questions/import-markdown}
 * (P2-C, issue #18) using MockMvc + Testcontainers. Exercises the full HTTP
 * stack: security filter chain, scoped exception handler, controller body
 * validation, authoritative Markdown parse, bank-contract preflight and the
 * transactional persistence service.
 *
 * <p>
 * Authorization matrix under test (deny by default, workspace boundary):
 * unauthenticated, STUDENT-only, ACADEMIC_ADMIN without teacher create
 * privilege, SYSTEM_ADMIN holding QUESTION_CREATE but no TEACHER role,
 * unrelated TEACHER, revoked org membership, missing bank, ARCHIVED bank —
 * all rejected; the bank creator (with or without a TeacherProfile) and
 * dual-role STUDENT+TEACHER users are accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class MarkdownQuestionImportApiIntegrationTests {

    private static final String ENDPOINT = "/api/question-banks/{bankId}/questions/import-markdown";

    private static final String SINGLE_CHOICE_DOC = """
            Câu 1 [SINGLE_CHOICE]: 2 + 2 bằng bao nhiêu?
            *A. 4
            B. 3
            C. 5
            D. 6
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private SchoolRepository schoolRepo;
    @Autowired
    private GradeLevelRepository glRepo;
    @Autowired
    private SubjectRepository subjectRepo;
    @Autowired
    private QuestionBankRepository bankRepo;
    @Autowired
    private QuestionBankService bankService;
    @Autowired
    private JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext
    private EntityManager em;

    private Long teacherUserId;
    private Long bankId;

    @BeforeEach
    void setUp() {
        User teacher = userRepo.saveAndFlush(
                new User("md-api-teacher", "mdapi@test.com", "hash", "MD API Teacher"));
        teacherUserId = teacher.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        School school = schoolRepo.saveAndFlush(new School("MD-API-SCH", "MD API School"));
        Long schoolId = school.getId();
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL-MDA", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "SUB-MDA", "Subject"));

        jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) "
                + "VALUES (" + teacherUserId + "," + schoolId + ",'TC-MDA')");

        bankId = bankService.createBank(teacherUserId,
                        new CreateQuestionBankRequest(null, "MD-API-BANK", "MD API Bank", null, subject.getId()))
                .id();
        em.clear();
    }

    @AfterEach
    void cleanupCommittedTestData() {
        jdbc.update("DELETE FROM question_options");
        jdbc.update("DELETE FROM question_versions");
        jdbc.update("DELETE FROM questions");
        jdbc.update("DELETE FROM question_banks WHERE code LIKE 'MD-%'");
        jdbc.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile')");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile')");
        jdbc.update("DELETE FROM teacher_profiles WHERE teacher_code = 'TC-MDA'");
        jdbc.update("DELETE FROM subjects WHERE code = 'SUB-MDA'");
        jdbc.update("DELETE FROM grade_levels WHERE code = 'GL-MDA'");
        jdbc.update("DELETE FROM schools WHERE code = 'MD-API-SCH'");
        jdbc.update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE code = 'SYSTEM_ADMIN') AND permission_id = (SELECT id FROM permissions WHERE code = 'QUESTION_CREATE')");
        // Personal workspaces provisioned by the no-profile test own FK rows
        // referencing the test user — remove them first.
        jdbc.update("DELETE FROM question_banks WHERE workspace_id IN (SELECT id FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile'))");
        jdbc.update("DELETE FROM workspace_memberships WHERE workspace_id IN (SELECT id FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile'))");
        jdbc.update("DELETE FROM workspaces WHERE owner_user_id IN (SELECT id FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'md-api-%' OR username = 'md-no-profile'");
    }

    private static JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(t -> t.subject(userId.toString()).claim("token_version", 0));
    }

    private static String jsonBody(String markdown) {
        return "{\"markdown\":\"" + markdown
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"}";
    }

    private int questionCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, bankId);
        return count == null ? 0 : count;
    }

    // ============================================================
    // Happy path
    // ============================================================

    @Test
    void importValidDocument_returns200PersistsAndIsListedInBank() throws Exception {
        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedRows").value(1))
                .andExpect(jsonPath("$.invalidRows").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        assertThat(questionCount()).isEqualTo(1);

        // Readback through the bank question list API (creator).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/question-banks/{bankId}/questions", bankId)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].questionType").value("SINGLE_CHOICE"));
    }

    @Test
    void dualRoleStudentTeacher_importAllowed() throws Exception {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", teacherUserId);

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(1));
    }

    // ============================================================
    // Authorization matrix (deny by default)
    // ============================================================

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(ENDPOINT, bankId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentOnly_returns403() throws Exception {
        User student = userRepo.saveAndFlush(
                new User("md-api-student", "mdastudent@test.com", "hash", "MD API Student"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(student.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void academicAdminWithoutTeacherRole_returns403() throws Exception {
        User admin = userRepo.saveAndFlush(
                new User("md-api-admin", "mdaadmin@test.com", "hash", "MD API Admin"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'ACADEMIC_ADMIN'", admin.getId());

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(admin.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void nonTeacherHoldingQuestionCreate_returns403() throws Exception {
        // SYSTEM_ADMIN is granted QUESTION_CREATE directly but holds no
        // TEACHER role — the import is a Teacher-only path.
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                + "SELECT (SELECT id FROM roles WHERE code = 'SYSTEM_ADMIN'), "
                + "(SELECT id FROM permissions WHERE code = 'QUESTION_CREATE')");
        User admin = userRepo.saveAndFlush(
                new User("md-api-sysadmin", "mdasys@test.com", "hash", "MD API SysAdmin"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'SYSTEM_ADMIN'", admin.getId());

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(admin.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void unrelatedTeacher_returns403() throws Exception {
        User other = userRepo.saveAndFlush(
                new User("md-api-other", "mdaother@test.com", "hash", "MD API Other"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(other.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
        assertThat(questionCount()).isZero();
    }

    @Test
    void revokedOrgMembership_returns403_evenForCreator() throws Exception {
        jdbc.update("""
                UPDATE workspace_memberships SET status = 'REMOVED'
                WHERE user_id = ? AND workspace_id = (
                    SELECT workspace_id FROM question_banks WHERE id = ?
                )
                """, teacherUserId, bankId);
        em.clear();

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void selfUpgradedTeacherWithoutProfile_importsIntoPersonalBank() throws Exception {
        User noProfile = userRepo.saveAndFlush(
                new User("md-no-profile", "mdnp@test.com", "hash", "No Profile Teacher"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", noProfile.getId());
        em.clear();

        Long personalBankId = bankService.createBank(noProfile.getId(),
                        new CreateQuestionBankRequest(null, "MD-PERSONAL", "Personal", null, null))
                .id();

        mockMvc.perform(post(ENDPOINT, personalBankId)
                        .with(jwtFor(noProfile.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(1));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, personalBankId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void missingBank_returns404() throws Exception {
        mockMvc.perform(post(ENDPOINT, 999999L)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_NOT_FOUND"));
    }

    @Test
    void archivedBank_returns403() throws Exception {
        jdbc.update("UPDATE question_banks SET status = 'ARCHIVED' WHERE id = ?", bankId);
        em.clear();

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(SINGLE_CHOICE_DOC)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    // ============================================================
    // Body validation / limits
    // ============================================================

    @Test
    void blankMarkdown_returns400() throws Exception {
        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    @Test
    void oversizedMarkdown_returns413() throws Exception {
        String big = "x".repeat(3) .repeat(MarkdownQuestionImportService.MAX_MARKDOWN_CHARS);

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(big)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_MARKDOWN_TOO_LARGE"));
    }

    @Test
    void tooManyQuestions_returns413() throws Exception {
        StringBuilder doc = new StringBuilder();
        for (int i = 1; i <= MarkdownQuestionImportService.MAX_MARKDOWN_QUESTIONS + 1; i++) {
            doc.append("Câu ").append(i).append(" [NUMERIC_FILL]: q").append(i).append("?\nĐáp án: 1.25\n\n");
        }

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc.toString())))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_MARKDOWN_TOO_MANY_QUESTIONS"));
        assertThat(questionCount()).isZero();
    }

    @Test
    void nonJsonContentType_returns415() throws Exception {
        // Without a `consumes` mapping attribute, a non-JSON body reaches the
        // controller's argument resolution, where message conversion fails
        // with HttpMediaTypeNotSupportedException — mapped by the scoped
        // import advice (a consumes mismatch would fail at handler mapping
        // with no handler-scoped advice and surface as a generic 500).
        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(SINGLE_CHOICE_DOC))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("QUESTION_IMPORT_FILE_INVALID"));
    }

    // ============================================================
    // Diagnostics: parse + bank-contract rejections persist nothing
    // ============================================================

    @Test
    void oneCorrectMultiple_returns200WithContractError_persistsNothing() throws Exception {
        String doc = """
                Câu 1 [MULTIPLE_CHOICE]: only one star.
                *A. one
                B. two
                C. three
                D. four
                """;

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.invalidRows").value(1))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("MULTIPLE_CHOICE_MIN_TWO_CORRECT"));

        assertThat(questionCount()).isZero();
    }

    @Test
    void leadingDotNumeric_returns200WithContractError_persistsNothing() throws Exception {
        String doc = """
                Câu 1 [NUMERIC_FILL]: Giá trị?
                Đáp án: -.50
                """;

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("NUMERIC_ANSWER_BANK_CONTRACT"));

        assertThat(questionCount()).isZero();
    }

    @Test
    void parseInvalidDocument_returns200WithParserDiagnostics() throws Exception {
        String doc = "Câu 2 [SINGLE_CHOICE]: numbering must start at one.\n*A. a\nB. b\nC. c\nD. d\n";

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc)))
                .andExpect(status().isOk())
                // Parser is all-or-nothing: the question count of an invalid
                // document is unknown → totalRows 0.
                .andExpect(jsonPath("$.totalRows").value(0))
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("QUESTION_NUMBER_MUST_START_AT_ONE"));

        assertThat(questionCount()).isZero();
    }

    @Test
    void documentLevelError_reportsRowZeroAndIsNotCountedAsQuestion() throws Exception {
        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("nothing recognizable here")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(0))
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.invalidRows").value(0))
                // Document-level diagnostics carry rowNumber 0 (order among
                // them follows parser emission) and are NOT counted as
                // rejected questions. Source line/column are preserved
                // verbatim from the authoritative parser (C-BE-01).
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(0))
                .andExpect(jsonPath("$.errors[0].line").value(1))
                .andExpect(jsonPath("$.errors[0].column").value(1))
                .andExpect(jsonPath("$.errors[?(@.code == 'NO_QUESTIONS')]").exists())
                .andExpect(jsonPath("$.errors[?(@.rowNumber > 0)]").isEmpty());

        assertThat(questionCount()).isZero();
    }

    @Test
    void parserDiagnostics_preserveExactSourceLineAndColumn() throws Exception {
        // Verbatim conformance-fixture document `numeric-token-too-long`:
        // the invalid token is anchored at line 2, column 11.
        String doc = "Câu 1 [NUMERIC_FILL]: v?\nĐáp án:   12345\n";

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedRows").value(0))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("NUMERIC_ANSWER_INVALID"))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(2))
                .andExpect(jsonPath("$.errors[0].column").value(11));

        assertThat(questionCount()).isZero();
    }

    @Test
    void bankContractErrors_haveNoSourceCoordinatesButKeepQuestionNumber() throws Exception {
        // Contract findings are keyed by question number; the parser never
        // emits them, so line/column stay null (C-BE-01: both shapes explicit).
        String doc = """
                Câu 1 [MULTIPLE_CHOICE]: one star.
                *A. one
                B. two
                C. three
                D. four
                """;

        mockMvc.perform(post(ENDPOINT, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(doc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(nullValue()))
                .andExpect(jsonPath("$.errors[0].column").value(nullValue()));
    }
}
