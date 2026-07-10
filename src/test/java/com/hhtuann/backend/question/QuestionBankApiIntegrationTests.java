package com.hhtuann.backend.question;

import com.hhtuann.backend.academic.domain.model.*;
import com.hhtuann.backend.academic.repository.*;
import com.hhtuann.backend.identity.domain.model.User;
import com.hhtuann.backend.identity.repository.UserRepository;
import com.hhtuann.backend.question.domain.model.*;
import com.hhtuann.backend.question.repository.*;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the three Question Bank endpoints (Batch B1) using
 * MockMvc + Testcontainers. Authorization is tested via ownership/school-scope,
 * not just URL security.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionBankApiIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private GradeLevelRepository glRepo;
    @Autowired private SubjectRepository subjectRepo;
    @Autowired private TeacherProfileRepository teacherRepo;
    @Autowired private QuestionBankRepository bankRepo;
    @Autowired private QuestionRepository questionRepo;
    @Autowired private QuestionVersionRepository versionRepo;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long teacherUserId;
    private Long teacherProfileId;
    private Long schoolId;
    private Long subjectId;
    private Long otherSchoolId;

    @BeforeEach
    void setUp() {
        User teacher = userRepo.saveAndFlush(
                new User("api-teacher", "apt@test.com", "hash", "API Teacher"));
        teacherUserId = teacher.getId();
        // Assign TEACHER role so the user has effective permissions (QUESTION_BANK_CREATE/READ, QUESTION_READ)
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

        School school = schoolRepo.saveAndFlush(new School("API-SCH", "API School"));
        schoolId = school.getId();
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(
                new Subject(schoolId, gl.getId(), "SUB", "Subject"));
        subjectId = subject.getId();

        TeacherProfile tp = teacherRepo.saveAndFlush(
                new TeacherProfile(teacherUserId, schoolId, "TC-API"));
        teacherProfileId = tp.getId();

        // A different school (for cross-school tests)
        otherSchoolId = schoolRepo.saveAndFlush(new School("API-SCH2", "API School 2")).getId();
    }

    // ==================== POST /api/question-banks ====================

    @Test
    void createBank_returns201() throws Exception {
        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK1", "Bank One", subjectId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BANK1"))
                .andExpect(jsonPath("$.name").value("Bank One"))
                .andExpect(jsonPath("$.questionCount").value(0))
                .andExpect(jsonPath("$.subject.id").value(subjectId.intValue()))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createBank_subjectFromDifferentSchool_returns403() throws Exception {
        GradeLevel gl2 = glRepo.saveAndFlush(new GradeLevel(otherSchoolId, "GL2", "G2"));
        Subject crossSubject = subjectRepo.saveAndFlush(
                new Subject(otherSchoolId, gl2.getId(), "XS", "Cross"));

        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK2", "Bank Two", crossSubject.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_SUBJECT_SCHOOL_MISMATCH"));
    }

    @Test
    void createBank_subjectNotFound_returns404() throws Exception {
        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK3", "Bank Three", 999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_SUBJECT_NOT_FOUND"));
    }

    @Test
    void createBank_duplicateCodeDifferentCase_returns409() throws Exception {
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "ALREADY", "Existing"));

        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("already", "Dup", subjectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_CODE_CONFLICT"));
    }

    @Test
    void createBank_noRoleOrPermission_returns403() throws Exception {
        // User has NO role assignments at all → QUESTION_BANK_CREATE permission
        // check fails before the teacher-profile resolution is reached.
        User nonTeacher = userRepo.saveAndFlush(
                new User("no-profile", "np@test.com", "hash", "No Profile"));

        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(nonTeacher.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK-X", "X", subjectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void createBank_teacherRoleWithoutProfile_returns404() throws Exception {
        // B1 gap: user HAS the TEACHER role (and thus QUESTION_BANK_CREATE),
        // but has no TeacherProfile row → must surface 404 not 403.
        User teacherNoProfile = userRepo.saveAndFlush(
                new User("teacher-no-tp", "tnp@test.com", "hash", "Teacher No TP"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                teacherNoProfile.getId());

        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherNoProfile.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK-NTP", "No TP", subjectId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_TEACHER_PROFILE_NOT_FOUND"));
    }

    @Test
    void createBank_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/question-banks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("BANK-U", "U", subjectId)))
                .andExpect(status().isUnauthorized());
    }

    // ==================== GET /api/question-banks/my ====================

    @Test
    void listMyBanks_returnsOnlyOwnedBanks() throws Exception {
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "MY-1", "My One"));

        // Another teacher's bank (same school) — must NOT appear
        User other = userRepo.saveAndFlush(new User("other-t", "ot@test.com", "hash", "Other"));
        TeacherProfile otherTp = teacherRepo.saveAndFlush(
                new TeacherProfile(other.getId(), schoolId, "TC-OTHER"));
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, otherTp.getId(), "OTHER-1", "Other Bank"));

        mockMvc.perform(get("/api/question-banks/my")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("MY-1"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listMyBanks_searchCaseInsensitive() throws Exception {
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "ALGEBRA", "Algebra Bank"));

        mockMvc.perform(get("/api/question-banks/my")
                        .param("search", "algebra")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("ALGEBRA"));
    }

    // ==================== GET /api/question-banks/{bankId}/questions ====================

    @Test
    void listQuestions_returnsCurrentVersionData() throws Exception {
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "QB-LIST", "QB List"));
        Question q = questionRepo.saveAndFlush(
                new Question(bank.getId(), "Q1", teacherUserId));
        versionRepo.saveAndFlush(new QuestionVersion(
                q.getId(), 1, QuestionType.SINGLE_CHOICE,
                "What is 1+1?", null, QuestionDifficulty.EASY,
                new BigDecimal("1.00"), null, null, teacherUserId));

        mockMvc.perform(get("/api/question-banks/{bankId}/questions", bank.getId())
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("Q1"))
                .andExpect(jsonPath("$.items[0].questionType").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.items[0].content").value("What is 1+1?"))
                .andExpect(jsonPath("$.items[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$.items[0].defaultPoints").value(1.00))
                // Must NOT expose answerKey/expectedAnswer/isCorrect
                .andExpect(jsonPath("$.items[0].answerKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].expectedAnswer").doesNotExist());
    }

    @Test
    void listQuestions_crossOwner_returns403() throws Exception {
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "QB-OWN", "QB Own"));

        User other = userRepo.saveAndFlush(new User("other-q", "oq@test.com", "hash", "Other Q"));
        TeacherProfile otherTp = teacherRepo.saveAndFlush(
                new TeacherProfile(other.getId(), schoolId, "TC-OQ"));

        mockMvc.perform(get("/api/question-banks/{bankId}/questions", bank.getId())
                        .with(jwtFor(other.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void listQuestions_bankNotFound_returns404() throws Exception {
        mockMvc.perform(get("/api/question-banks/{bankId}/questions", 999999L)
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_NOT_FOUND"));
    }

    @Test
    void listQuestions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/question-banks/{bankId}/questions", 1L))
                .andExpect(status().isUnauthorized());
    }

    // ==================== M2: Permission revoked tests ====================

    @Test
    void createBank_permissionRevoked_returns403() throws Exception {
        revokePermission("QUESTION_BANK_CREATE");
        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bankRequestJson("PERM-C", "PermC", subjectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void listMyBanks_permissionRevoked_returns403() throws Exception {
        revokePermission("QUESTION_BANK_READ");
        mockMvc.perform(get("/api/question-banks/my")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void listQuestions_permissionRevoked_returns403() throws Exception {
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "PERM-Q", "PermQ"));
        revokePermission("QUESTION_READ");
        mockMvc.perform(get("/api/question-banks/{id}/questions", bank.getId())
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    // ==================== M3: Filter + pagination tests ====================

    @Test
    void listQuestions_filterByType() throws Exception {
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "QB-TYPE", "QB Type"));
        Question q1 = questionRepo.saveAndFlush(new Question(bank.getId(), "Q-S", teacherUserId));
        versionRepo.saveAndFlush(new QuestionVersion(q1.getId(), 1,
                QuestionType.SINGLE_CHOICE, "single?", null, QuestionDifficulty.EASY,
                new BigDecimal("1.00"), null, null, teacherUserId));
        Question q2 = questionRepo.saveAndFlush(new Question(bank.getId(), "Q-M", teacherUserId));
        versionRepo.saveAndFlush(new QuestionVersion(q2.getId(), 1,
                QuestionType.MULTIPLE_CHOICE, "multi?", null, QuestionDifficulty.MEDIUM,
                new BigDecimal("2.00"), null, null, teacherUserId));

        mockMvc.perform(get("/api/question-banks/{id}/questions", bank.getId())
                        .param("type", "SINGLE_CHOICE")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].questionType").value("SINGLE_CHOICE"));
    }

    @Test
    void listQuestions_paginationMetadata() throws Exception {
        QuestionBank bank = bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "QB-PAGE", "QB Page"));
        for (int i = 1; i <= 5; i++) {
            Question q = questionRepo.saveAndFlush(
                    new Question(bank.getId(), "QP-" + i, teacherUserId));
            versionRepo.saveAndFlush(new QuestionVersion(q.getId(), 1,
                    QuestionType.SINGLE_CHOICE, "q" + i, null, QuestionDifficulty.EASY,
                    new BigDecimal("1.00"), null, null, teacherUserId));
        }

        mockMvc.perform(get("/api/question-banks/{id}/questions", bank.getId())
                        .param("page", "0").param("size", "2")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void listMyBanks_subjectIdFilter() throws Exception {
        GradeLevel gl = glRepo.findAll().get(0);
        Subject subject2 = subjectRepo.saveAndFlush(
                new Subject(schoolId, gl.getId(), "SUB2", "Subject 2"));
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subjectId, teacherProfileId, "F-S1", "Filter Subj1"));
        bankRepo.saveAndFlush(new QuestionBank(
                schoolId, subject2.getId(), teacherProfileId, "F-S2", "Filter Subj2"));

        mockMvc.perform(get("/api/question-banks/my")
                        .param("subjectId", subjectId.toString())
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("F-S1"));
    }

    // ==================== M4: Validation error code test ====================

    @Test
    void createBank_codeBlank_isAutoGenerated() throws Exception {
        // code is now optional (auto-generated server-side); a blank code succeeds with a non-empty auto code.
        mockMvc.perform(post("/api/question-banks")
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"Empty\",\"subjectId\":" + subjectId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    // ==================== B2.1: Import template tests ====================

    @Test
    void getImportTemplate_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/questions/import-template"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getImportTemplate_studentWithoutPermission_returns403() throws Exception {
        User student = userRepo.saveAndFlush(
                new User("import-student", "is@test.com", "hash", "Import Student"));
        // STUDENT role has no QUESTION_CREATE permission.
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

        mockMvc.perform(get("/api/questions/import-template")
                        .with(jwtFor(student.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void getImportTemplate_teacherPermissionRevoked_returns403() throws Exception {
        revokePermission("QUESTION_CREATE");
        mockMvc.perform(get("/api/questions/import-template")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    @Test
    void getImportTemplate_teacherWithPermission_returns200AndValidXlsx() throws Exception {
        byte[] bytes = mockMvc.perform(get("/api/questions/import-template")
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"quizopia-question-import-template.xlsx\""))
                .andReturn().getResponse().getContentAsByteArray();

        // Parse the response and assert structure.
        try (org.apache.poi.ss.usermodel.Workbook wb =
                     org.apache.poi.ss.usermodel.WorkbookFactory
                             .create(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("Questions");
            assertThat(wb.getSheetName(1)).isEqualTo("Instructions");

            org.apache.poi.ss.usermodel.Sheet q = wb.getSheetAt(0);
            // 22 headers in order (rounding_instruction removed)
            org.apache.poi.ss.usermodel.Row header = q.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 22);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("question_code");
            assertThat(header.getCell(21).getStringCellValue()).isEqualTo("explanation");

            // 4 example rows, one per type (rows 1-4)
            assertThat(q.getRow(1).getCell(1).getStringCellValue()).isEqualTo("SINGLE_CHOICE");
            assertThat(q.getRow(2).getCell(1).getStringCellValue()).isEqualTo("MULTIPLE_CHOICE");
            assertThat(q.getRow(3).getCell(1).getStringCellValue()).isEqualTo("TRUE_FALSE_MATRIX");
            assertThat(q.getRow(4).getCell(1).getStringCellValue()).isEqualTo("NUMERIC_FILL");

            // numeric_answer (col 20) on the numeric example is a STRING cell,
            // value "2.50" (4 chars).
            org.apache.poi.ss.usermodel.Cell num = q.getRow(4).getCell(20);
            assertThat(num.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(num.getStringCellValue()).isEqualTo("2.50");
            assertThat(num.getStringCellValue().length()).isEqualTo(4);

            // No formulas anywhere in the Questions sheet data region.
            boolean hasFormula = false;
            for (int r = 0; r <= q.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = q.getRow(r);
                if (row == null) continue;
                for (short c = 0; c < row.getLastCellNum(); c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                        hasFormula = true;
                    }
                }
            }
            assertThat(hasFormula).isFalse();
        }
    }

    @Test
    void templateEndpoint_nonTeacherWithQuestionCreate_returns403() throws Exception {
        // Create a user with SYSTEM_ADMIN role (NOT TEACHER)
        User admin = userRepo.saveAndFlush(
                new User("admin-tpl", "atpl@test.com", "hash", "Admin"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'SYSTEM_ADMIN'", admin.getId());
        // Temporarily grant QUESTION_CREATE to SYSTEM_ADMIN role
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                + "SELECT r.id, p.id FROM roles r, permissions p "
                + "WHERE r.code = 'SYSTEM_ADMIN' AND p.code = 'QUESTION_CREATE'");

        mockMvc.perform(get("/api/questions/import-template")
                        .with(jwtFor(admin.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
    }

    // ==================== Helpers ====================

    private void revokePermission(String permCode) {
        jdbc.update("DELETE FROM role_permissions "
                        + "WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER') "
                        + "AND permission_id = (SELECT id FROM permissions WHERE code = ?)",
                permCode);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
    }

    private static String bankRequestJson(String code, String name, Long subjectId) {
        return String.format("{\"code\":\"%s\",\"name\":\"%s\",\"subjectId\":%d}", code, name, subjectId);
    }
}
