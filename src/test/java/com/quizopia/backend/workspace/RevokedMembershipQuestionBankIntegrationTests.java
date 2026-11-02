package com.quizopia.backend.workspace;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.question.exception.QuestionException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.workspace.repository.WorkspaceMembershipRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-2 reproduction (issue #4): a QUESTION BANK creator whose organization
 * workspace membership is no longer ACTIVE must lose all access to that
 * bank — creation-time ownership must not override revoked membership.
 * Personal-workspace owners keep full access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class RevokedMembershipQuestionBankIntegrationTests {

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
        private TeacherProfileRepository teacherRepo;
        @Autowired
        private QuestionBankService bankService;
        @Autowired
        private WorkspaceRepository workspaceRepo;
        @Autowired
        private WorkspaceMembershipRepository membershipRepo;
        @Autowired
        private com.quizopia.backend.workspace.application.WorkspaceProvisioningService provisioningService;
        @Autowired
        private JdbcTemplate jdbc;
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

        private Long teacherUserId;
        private Long schoolId;
        private Long subjectId;

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("rv-qb-teacher", "rvqt@test.com", "hash", "RV QB Teacher"));
                teacherUserId = teacher.getId();
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);
                School school = schoolRepo.saveAndFlush(new School("RV-QB-SCH", "RV QB School"));
                schoolId = school.getId();
                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "GL", "Grade"));
                Subject subject = subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "SUB", "Subject"));
                subjectId = subject.getId();
                teacherRepo.saveAndFlush(new TeacherProfile(teacherUserId, schoolId, "TC-RVQB"));
        }

        private Long createOrgBank() {
                return bankService.createBank(teacherUserId,
                                new CreateQuestionBankRequest(null, "RV-BANK", "Revoked Bank", null, subjectId)).id();
        }

        private void setMembershipStatus(String status) {
                jdbc.update("UPDATE workspace_memberships SET status = ? "
                                + "WHERE workspace_id = (SELECT workspace_id FROM question_banks WHERE id = "
                                + "(SELECT id FROM question_banks WHERE created_by_user_id = " + teacherUserId
                                + " AND code = 'RV-BANK' LIMIT 1)) AND user_id = " + teacherUserId, status);
                // Detach the pre-revocation (ACTIVE) membership so the service
                // re-reads it — production revocation happens in another session.
                em.clear();
        }

        // ---- Organization workspace: revoked membership blocks the creator ----

        @Test
        void suspendedCreatorCannotReadOrgBankQuestions() {
                Long bankId = createOrgBank();
                setMembershipStatus("SUSPENDED");
                assertThatThrownBy(() -> bankService.listQuestions(
                                teacherUserId, bankId, null, null, null, 0, 20))
                                .isInstanceOf(QuestionException.class);
        }

        @Test
        void removedCreatorCannotSeeOrgBankInMyList() {
                createOrgBank();
                setMembershipStatus("REMOVED");
                var page = bankService.listMyBanks(teacherUserId, null, null, 0, 20, null);
                assertThat(page.items()).isEmpty();
        }

        @Test
        void removedCreatorCannotCreateInOrgWorkspace() throws Exception {
                createOrgBank();
                setMembershipStatus("REMOVED");
                mockMvc.perform(post("/api/question-banks")
                                .with(jwtFor(teacherUserId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"RV-BANK-2\",\"name\":\"Another\",\"subjectId\":" + subjectId + "}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void activeCreatorKeepsAccess() {
                Long bankId = createOrgBank();
                setMembershipStatus("ACTIVE");
                bankService.listQuestions(teacherUserId, bankId, null, null, null, 0, 20); // no exception
        }

        // ---- Personal workspace: owner unaffected ----

        @Test
        void personalOwnerKeepsFullAccess() throws Exception {
                // Teacher has a profile (org default) — target the personal workspace explicitly.
                User teacher = userRepo.findById(teacherUserId).orElseThrow();
                Long personalWsId = provisioningService.ensurePersonalWorkspace(teacher).getId();
                mockMvc.perform(post("/api/question-banks")
                                .with(jwtFor(teacherUserId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"PERS\",\"name\":\"My Personal\",\"workspaceId\":" + personalWsId + "}"))
                                .andExpect(status().isCreated());
                var page = bankService.listMyBanks(teacherUserId, null, null, 0, 20, null);
                assertThat(page.items()).isNotEmpty();
        }

        @Test
        void personalBankListedWhileOrgRevoked() {
                createOrgBank();
                setMembershipStatus("REMOVED");
                // create a personal bank too (explicit personal workspace)
                User teacher = userRepo.findById(teacherUserId).orElseThrow();
                Long personalWsId = provisioningService.ensurePersonalWorkspace(teacher).getId();
                bankService.createBank(teacherUserId,
                                new CreateQuestionBankRequest(personalWsId, "PERS2", "Personal 2", null, null));
                var page = bankService.listMyBanks(teacherUserId, null, null, 0, 20, null);
                // org bank hidden, personal bank visible
                assertThat(page.items()).extracting(b -> b.code()).containsExactly("PERS2");
        }

        private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
                return SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
        }
}
