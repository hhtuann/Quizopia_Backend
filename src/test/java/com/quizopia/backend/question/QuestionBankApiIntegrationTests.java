package com.quizopia.backend.question;

import com.quizopia.backend.academic.domain.model.AcademicStatus;
import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.domain.model.*;
import com.quizopia.backend.question.repository.*;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
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
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the three Question Bank endpoints (Batch B1) using
 * MockMvc + Testcontainers. Authorization is tested via user ownership,
 * not just URL security (user-centric model: banks are owned by their
 * creating user; subjects come from the global academic catalog).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class QuestionBankApiIntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private UserRepository userRepo;
        @Autowired
        private GradeLevelRepository glRepo;
        @Autowired
        private SubjectRepository subjectRepo;
        @Autowired
        private QuestionBankRepository bankRepo;
        @Autowired
        private QuestionRepository questionRepo;
        @Autowired
        private QuestionVersionRepository versionRepo;
        @Autowired
        private org.springframework.jdbc.core.JdbcTemplate jdbc;

        private Long teacherUserId;
        private Long gradeLevelId;
        private Long subjectId;

        /** Persists a bank owned by the fixture teacher. */
        private QuestionBank saveBank(Long subjId, String code, String name) {
                return bankRepo.saveAndFlush(new QuestionBank(
                                teacherUserId, subjId, code, name));
        }

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("api-teacher", "apt@test.com", "hash", "API Teacher"));
                teacherUserId = teacher.getId();
                // Assign TEACHER role so the user has effective permissions
                // (QUESTION_BANK_CREATE/READ, QUESTION_READ)
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);

                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel("GL-API", "Grade"));
                gradeLevelId = gl.getId();
                Subject subject = subjectRepo.saveAndFlush(
                                new Subject(gradeLevelId, "SUB-API", "Subject"));
                subjectId = subject.getId();
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
        void createBank_withoutSubject_succeedsWithNullSubject() throws Exception {
                // subjectId is optional for every teacher (global catalog metadata).
                mockMvc.perform(post("/api/question-banks")
                                .with(jwtFor(teacherUserId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"BANK-NOSUBJ\",\"name\":\"No Subject\"}"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.code").value("BANK-NOSUBJ"))
                                .andExpect(jsonPath("$.subject").value(nullValue()));
        }

        @Test
        void createBank_inactiveSubject_returns404() throws Exception {
                // Global-catalog subjects are referenceable only while ACTIVE.
                Subject inactive = subjectRepo.saveAndFlush(
                                new Subject(gradeLevelId, "SUB-INACT", "Inactive Subject"));
                inactive.setStatus(AcademicStatus.INACTIVE);
                subjectRepo.saveAndFlush(inactive);

                mockMvc.perform(post("/api/question-banks")
                                .with(jwtFor(teacherUserId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bankRequestJson("BANK-INA", "Inactive", inactive.getId())))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("QUESTION_SUBJECT_NOT_FOUND"));
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
                saveBank(subjectId, "ALREADY", "Existing");

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
                // check fails before ownership resolution is reached.
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
        void createBank_unauthenticated_returns401() throws Exception {
                mockMvc.perform(post("/api/question-banks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bankRequestJson("BANK-U", "U", subjectId)))
                                .andExpect(status().isUnauthorized());
        }

        // ==================== GET /api/question-banks/my ====================

        @Test
        void listMyBanks_returnsOnlyOwnedBanks() throws Exception {
                saveBank(subjectId, "MY-1", "My One");

                // Another teacher's bank — must NOT appear
                User other = userRepo.saveAndFlush(new User("other-t", "ot@test.com", "hash", "Other"));
                bankRepo.saveAndFlush(new QuestionBank(
                                other.getId(), subjectId, "OTHER-1", "Other Bank"));

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
                saveBank(subjectId, "ALGEBRA", "Algebra Bank");

                mockMvc.perform(get("/api/question-banks/my")
                                .param("search", "algebra")
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].code").value("ALGEBRA"));
        }

        // ==================== GET /api/question-banks/{bankId}/questions
        // ====================

        @Test
        void listQuestions_returnsCurrentVersionData() throws Exception {
                QuestionBank bank = saveBank(subjectId, "QB-LIST", "QB List");
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
                QuestionBank bank = saveBank(subjectId, "QB-OWN", "QB Own");

                // A fully-privileged TEACHER (QUESTION_READ passes) who is NOT
                // the bank creator → ownership check denies.
                User other = userRepo.saveAndFlush(new User("other-q", "oq@test.com", "hash", "Other Q"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());

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
                QuestionBank bank = saveBank(subjectId, "PERM-Q", "PermQ");
                revokePermission("QUESTION_READ");
                mockMvc.perform(get("/api/question-banks/{id}/questions", bank.getId())
                                .with(jwtFor(teacherUserId)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("QUESTION_BANK_ACCESS_DENIED"));
        }

        // ==================== M3: Filter + pagination tests ====================

        @Test
        void listQuestions_filterByType() throws Exception {
                QuestionBank bank = saveBank(subjectId, "QB-TYPE", "QB Type");
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
                QuestionBank bank = saveBank(subjectId, "QB-PAGE", "QB Page");
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
                Subject subject2 = subjectRepo.saveAndFlush(
                                new Subject(gradeLevelId, "SUB-API2", "Subject 2"));
                saveBank(subjectId, "F-S1", "Filter Subj1");
                saveBank(subject2.getId(), "F-S2", "Filter Subj2");

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
                // code is now optional (auto-generated server-side); a blank code succeeds with
                // a non-empty auto code.
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
                try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory
                                .create(new java.io.ByteArrayInputStream(bytes))) {
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        assertThat(wb.getSheetName(0)).isEqualTo("Questions");
                        assertThat(wb.getSheetName(1)).isEqualTo("Instructions");

                        org.apache.poi.ss.usermodel.Sheet q = wb.getSheetAt(0);
                        // 9 headers in order (matches QuestionTemplateService.HEADERS and
                        // ExcelQuestionParser.EXPECTED_HEADERS).
                        org.apache.poi.ss.usermodel.Row header = q.getRow(0);
                        assertThat(header.getLastCellNum()).isEqualTo((short) 9);
                        assertThat(header.getCell(0).getStringCellValue()).isEqualTo("question_type");
                        assertThat(header.getCell(8).getStringCellValue()).isEqualTo("explanation");

                        // 4 example rows, one per type (rows 1-4); type is at col 0.
                        assertThat(q.getRow(1).getCell(0).getStringCellValue()).isEqualTo("SINGLE_CHOICE");
                        assertThat(q.getRow(2).getCell(0).getStringCellValue()).isEqualTo("MULTIPLE_CHOICE");
                        assertThat(q.getRow(3).getCell(0).getStringCellValue()).isEqualTo("TRUE_FALSE_MATRIX");
                        assertThat(q.getRow(4).getCell(0).getStringCellValue()).isEqualTo("NUMERIC_FILL");

                        // correct_answers (col 7) on the numeric example is a STRING cell,
                        // value "2.50" (4 chars, Text format keeps it verbatim).
                        org.apache.poi.ss.usermodel.Cell num = q.getRow(4).getCell(7);
                        assertThat(num.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                        assertThat(num.getStringCellValue()).isEqualTo("2.50");
                        assertThat(num.getStringCellValue().length()).isEqualTo(4);

                        // No formulas anywhere in the Questions sheet data region.
                        boolean hasFormula = false;
                        for (int r = 0; r <= q.getLastRowNum(); r++) {
                                org.apache.poi.ss.usermodel.Row row = q.getRow(r);
                                if (row == null)
                                        continue;
                                for (short c = 0; c < row.getLastCellNum(); c++) {
                                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                                        if (cell != null && cell
                                                        .getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                                                hasFormula = true;
                                        }
                                }
                        }
                        assertThat(hasFormula).isFalse();
                }
        }

        @Test
        void templateEndpoint_nonTeacherWithQuestionCreate_returns403() throws Exception {
                // A STUDENT-role user (NOT TEACHER) granted QUESTION_CREATE:
                // the permission alone must not unlock the Teacher-only template.
                User student = userRepo.saveAndFlush(
                                new User("student-tpl", "stpl@test.com", "hash", "Student"));
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());
                // Temporarily grant QUESTION_CREATE to the STUDENT role
                jdbc.update("INSERT INTO role_permissions (role_id, permission_id) "
                                + "SELECT r.id, p.id FROM roles r, permissions p "
                                + "WHERE r.code = 'STUDENT' AND p.code = 'QUESTION_CREATE'");

                mockMvc.perform(get("/api/questions/import-template")
                                .with(jwtFor(student.getId())))
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
