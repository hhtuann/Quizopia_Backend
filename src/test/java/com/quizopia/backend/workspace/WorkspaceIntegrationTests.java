package com.quizopia.backend.workspace;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.School;
import com.quizopia.backend.academic.domain.model.StudentProfile;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.domain.model.TeacherProfile;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SchoolRepository;
import com.quizopia.backend.academic.repository.StudentProfileRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.academic.repository.TeacherProfileRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.workspace.application.WorkspaceAccessService;
import com.quizopia.backend.workspace.application.WorkspaceProvisioningService;
import com.quizopia.backend.workspace.domain.model.WorkspaceMembership;
import com.quizopia.backend.workspace.domain.model.WorkspaceRole;
import com.quizopia.backend.workspace.repository.WorkspaceMembershipRepository;
import com.quizopia.backend.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public platform foundation (issue #1 A1/A2): workspaces, memberships,
 * provisioning and the workspace-scoped authorization checks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class WorkspaceIntegrationTests {

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
        private StudentProfileRepository studentRepo;
        @Autowired
        private WorkspaceRepository workspaceRepo;
        @Autowired
        private WorkspaceMembershipRepository membershipRepo;
        @Autowired
        private WorkspaceProvisioningService provisioningService;
        @Autowired
        private WorkspaceAccessService accessService;
        @Autowired
        private JdbcTemplate jdbc;
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

        private Long teacherUserId;
        private Long schoolId;

        @BeforeEach
        void setUp() {
                User teacher = userRepo.saveAndFlush(
                                new User("ws-teacher", "wst@test.com", "hash", "WS Teacher"));
                teacherUserId = teacher.getId();
                jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);
                School school = schoolRepo.saveAndFlush(new School("WS-SCH", "WS School"));
                schoolId = school.getId();
                GradeLevel gl = glRepo.saveAndFlush(new GradeLevel(schoolId, "WS-GL", "Grade"));
                subjectRepo.saveAndFlush(new Subject(schoolId, gl.getId(), "WS-SUB", "Subject"));
                teacherRepo.saveAndFlush(new TeacherProfile(teacherUserId, schoolId, "TC-WS"));
        }

        private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtFor(Long userId) {
                return SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(token -> token.subject(userId.toString()).claim("token_version", 0));
        }

        // ==================== provisioning ====================

        @Test
        void provisioningIsIdempotentAndTransactional() {
                User user = userRepo.saveAndFlush(
                                new User("ws-user", "wsu@test.com", "hash", "WS User"));
                var ws1 = provisioningService.ensurePersonalWorkspace(user);
                var ws2 = provisioningService.ensurePersonalWorkspace(user);
                assertThat(ws1.getId()).isEqualTo(ws2.getId());
                assertThat(workspaceRepo.findByOwnerUserIdAndWorkspaceType(
                                user.getId(),
                                com.quizopia.backend.workspace.domain.model.WorkspaceType.PERSONAL))
                                .hasValueSatisfying(ws -> {
                                        assertThat(ws.getOwnerUserId()).isEqualTo(user.getId());
                                });
                // exactly one PERSONAL workspace + one OWNER membership
                Long wsCount = jdbc.queryForObject(
                                "SELECT count(*) FROM workspaces WHERE owner_user_id = " + user.getId()
                                                + " AND workspace_type = 'PERSONAL'",
                                Long.class);
                assertThat(wsCount).isEqualTo(1);
                Long ownerMemberships = jdbc.queryForObject(
                                "SELECT count(*) FROM workspace_memberships WHERE workspace_id = " + ws1.getId(),
                                Long.class);
                assertThat(ownerMemberships).isEqualTo(1);
        }

        @Test
        void registrationProvisionsPersonalWorkspace() throws Exception {
                String username = "ws-reg-" + System.nanoTime();
                mockMvc.perform(post("/api/auth/register")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"Reg User\",\"phone\":\"0900000001\","
                                                + "\"accountType\":\"TEACHER\"}"))
                                .andExpect(status().isCreated());

                Long userId = jdbc.queryForObject(
                                "SELECT id FROM users WHERE username = '" + username + "'", Long.class);
                Long wsCount = jdbc.queryForObject(
                                "SELECT count(*) FROM workspaces WHERE owner_user_id = " + userId
                                                + " AND workspace_type = 'PERSONAL'",
                                Long.class);
                assertThat(wsCount).isEqualTo(1);
        }

        @Test
        void teacherRegistrationWithoutInviteIsAllowed() throws Exception {
                // Public platform user (issue #1 A2): no school, no invite.
                String username = "ws-open-" + System.nanoTime();
                mockMvc.perform(post("/api/auth/register")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"Open Creator\",\"phone\":\"0900000002\","
                                                + "\"accountType\":\"TEACHER\"}"))
                                .andExpect(status().isCreated());
        }

        // ==================== GET /api/workspaces/my ====================

        @Test
        void myWorkspacesReturnsPersonalAndOrganization() throws Exception {
                // Personal workspace for teacher
                User teacher = userRepo.findById(teacherUserId).orElseThrow();
                provisioningService.ensurePersonalWorkspace(teacher);
                // Teacher profile school → organization + EDITOR membership (V10 rules)
                jdbc.update("INSERT INTO organizations (legacy_school_id, code, name) "
                                + "VALUES (?, 'WS-ORG', 'WS Org')", schoolId);
                Long orgId = jdbc.queryForObject(
                                "SELECT id FROM organizations WHERE legacy_school_id = " + schoolId, Long.class);
                jdbc.update("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION', 'WS Org', ?)", orgId);
                Long orgWsId = jdbc.queryForObject(
                                "SELECT id FROM workspaces WHERE organization_id = " + orgId, Long.class);
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (?, ?, 'EDITOR', 'ACTIVE')", orgWsId, teacherUserId);

                mockMvc.perform(get("/api/workspaces/my").with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaces.length()").value(2))
                                .andExpect(jsonPath("$.personalWorkspaceId").isNotEmpty());
        }

        @Test
        void myWorkspaces_neverLeaksOtherUsersPersonalWorkspaces() throws Exception {
                User other = userRepo.saveAndFlush(
                                new User("ws-other", "wso@test.com", "hash", "WS Other"));
                provisioningService.ensurePersonalWorkspace(other);

                mockMvc.perform(get("/api/workspaces/my").with(jwtFor(teacherUserId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaces.length()").value(0))
                                .andExpect(jsonPath("$.personalWorkspaceId").doesNotExist());
        }

        @Test
        void myWorkspaces_unauthenticated_returns401() throws Exception {
                mockMvc.perform(get("/api/workspaces/my"))
                                .andExpect(status().isUnauthorized());
        }

        // ==================== access policy ====================

        @Test
        void inactiveMembershipIsDenied() {
                User user = userRepo.saveAndFlush(
                                new User("ws-susp", "wss@test.com", "hash", "WS Susp"));
                var ws = provisioningService.ensurePersonalWorkspace(user);
                Long membershipId = jdbc.queryForObject(
                                "SELECT id FROM workspace_memberships WHERE workspace_id = " + ws.getId()
                                                + " AND user_id = " + user.getId(), Long.class);
                jdbc.update("UPDATE workspace_memberships SET status = 'SUSPENDED' WHERE id = "
                                + membershipId);
                em.clear(); // bypass first-level cache so the policy check sees SUSPENDED
                assertThatThrownBy(() -> accessService.requireActiveMember(user.getId(), ws.getId()))
                                .isInstanceOf(com.quizopia.backend.workspace.exception.WorkspaceException.class);
        }

        @Test
        void roleEscalationIsDenied() {
                User student = userRepo.saveAndFlush(
                                new User("ws-stu", "wsstu@test.com", "hash", "WS Student"));
                User owner = userRepo.saveAndFlush(
                                new User("ws-own", "wsown@test.com", "hash", "WS Owner"));
                var ws = provisioningService.ensurePersonalWorkspace(owner);
                // student is NOT a member at all
                assertThatThrownBy(() -> accessService.requireContentEditor(student.getId(), ws.getId()))
                                .isInstanceOf(com.quizopia.backend.workspace.exception.WorkspaceException.class);
        }

        @Test
        void memberRoleCannotPassEditorCheck() {
                User owner = userRepo.saveAndFlush(
                                new User("ws-own2", "wsown2@test.com", "hash", "WS Owner 2"));
                User member = userRepo.saveAndFlush(
                                new User("ws-mem", "wsmem@test.com", "hash", "WS Member"));
                var ws = provisioningService.ensurePersonalWorkspace(owner);
                membershipRepo.saveAndFlush(new WorkspaceMembership(
                                ws.getId(), member.getId(), WorkspaceRole.MEMBER));
                assertThatThrownBy(() -> accessService.requireContentEditor(member.getId(), ws.getId()))
                                .isInstanceOf(com.quizopia.backend.workspace.exception.WorkspaceException.class);
                // MEMBER still passes the plain active-member check
                accessService.requireActiveMember(member.getId(), ws.getId());
        }

        @Test
        void unknownWorkspaceIs404() {
                assertThatThrownBy(() -> accessService.requireActiveMember(teacherUserId, 999999L))
                                .isInstanceOf(com.quizopia.backend.workspace.exception.WorkspaceException.class)
                                .hasMessageContaining("Workspace not found");
        }

        // ==================== V10 backfill semantics ====================

        @Test
        void legacyProfilesGainOrganizationMemberships() {
                // Simulate the V10 backfill rules for a student profile in the school.
                User student = userRepo.saveAndFlush(
                                new User("ws-stu2", "wsstu2@test.com", "hash", "WS Student 2"));
                studentRepo.saveAndFlush(new StudentProfile(student.getId(), schoolId, "STU-WS"));
                jdbc.update("INSERT INTO organizations (legacy_school_id, code, name) "
                                + "VALUES (?, 'WS-ORG2', 'WS Org 2')", schoolId);
                Long orgId = jdbc.queryForObject(
                                "SELECT id FROM organizations WHERE legacy_school_id = " + schoolId, Long.class);
                jdbc.update("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION', 'WS Org 2', ?)", orgId);

                Long memberCount = jdbc.queryForObject(
                                "SELECT count(*) FROM workspace_memberships m "
                                                + "JOIN workspaces w ON w.id = m.workspace_id "
                                                + "WHERE w.organization_id = " + orgId
                                                + " AND m.user_id = " + student.getId(),
                                Long.class);
                assertThat(memberCount).isEqualTo(0); // not backfilled by hand-insert — V10 only backfills at migration time
        }

        // ==================== personal authoring (acceptance) ====================

        @Test
        void unaffiliatedUserCreatesPersonalQuestionBank() throws Exception {
                // Phase 1 acceptance (issue #1): a newly registered, unaffiliated user
                // can create and own personal question resources without a school
                // invitation or TeacherProfile.
                String username = "ws-creator-" + System.nanoTime();
                mockMvc.perform(post("/api/auth/register")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"Solo Creator\",\"phone\":\"0900000003\","
                                                + "\"accountType\":\"TEACHER\"}"))
                                .andExpect(status().isCreated());
                Long userId = jdbc.queryForObject(
                                "SELECT id FROM users WHERE username = '" + username + "'", Long.class);

                mockMvc.perform(post("/api/question-banks")
                                .with(jwtFor(userId))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"PERSONAL-1\",\"name\":\"My Personal Bank\"}"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.code").value("PERSONAL-1"));

                Long personalBanks = jdbc.queryForObject(
                                "SELECT count(*) FROM question_banks qb "
                                                + "JOIN workspaces w ON w.id = qb.workspace_id "
                                                + "WHERE w.workspace_type = 'PERSONAL' AND qb.created_by_user_id = "
                                                + userId,
                                Long.class);
                assertThat(personalBanks).isEqualTo(1);
        }
}
