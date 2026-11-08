package com.quizopia.backend.classroom;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CLASS-M1 (issue #50): HTTP integration tests for the learner membership
 * projection {@code GET /api/classrooms/joined} and the self-exit
 * {@code POST /api/classrooms/{id}/leave}.
 *
 * <p>Covers: multi-teacher multi-class membership from BOTH enrollment
 * sources (direct add + verified-email roster link) listed exactly once;
 * the leave consent lifecycle for each source (synthetic opt-out for
 * direct add, DECLINED transition for roster-linked); repeat-leave and
 * cross-account 404s; paging with stable order; class metadata enrichment
 * of learner roster responses; teacher flows untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomMembershipApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private String tag;
    private long teacherA;
    private long teacherB;
    private long learner;

    @BeforeEach
    void setUp() {
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherA = insertUser("tcha" + tag, "Teacher A " + tag);
        teacherB = insertUser("tchb" + tag, "Teacher B " + tag);
        assignRole(teacherA, "TEACHER");
        assignRole(teacherB, "TEACHER");
        learner = verifiedStudent("lrnr" + tag, "Learner " + tag);
    }

    // ============================================================
    // GET /api/classrooms/joined — projection
    // ============================================================

    @Test
    void joinedListsMultiTeacherClassesFromBothSourcesExactlyOnce() throws Exception {
        long classA = createClassroom(teacherA, "A-" + tag, "Alpha " + tag);
        long classB = createClassroom(teacherB, "B-" + tag, "Beta " + tag);
        // Source 1: teacher A direct add.
        addMemberDirect(teacherA, classA, learner);
        // Source 2: teacher B roster invite — verified email auto-links.
        addRosterEntry(teacherB, classB, "Learner " + tag, "lrnr" + tag + "@t.com");

        MvcResult joined = mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        JsonNode items = objectMapper.readTree(joined.getResponse().getContentAsString()).get("items");
        JsonNode alpha = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("classroomId").asLong() == classA).toList().get(0);
        JsonNode beta = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(i -> i.get("classroomId").asLong() == classB).toList().get(0);
        assertThat(alpha.get("name").asString()).isEqualTo("Alpha " + tag);
        assertThat(alpha.get("teacherDisplayName").asString()).isEqualTo("Teacher A " + tag);
        assertThat(alpha.get("code").asString()).isEqualTo("A-" + tag);
        assertThat(alpha.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(alpha.get("joinedAt")).isNotNull();
        assertThat(beta.get("teacherDisplayName").asString()).isEqualTo("Teacher B " + tag);
        assertThat(beta.get("status").asString()).isEqualTo("ACTIVE");
        // No duplicate membership of either class.
        Integer countA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members WHERE classroom_id=" + classA
                        + " AND user_id=" + learner, Integer.class);
        assertThat(countA).isEqualTo(1);
    }

    @Test
    void joinedIsEmptyForLearnerWithNoClasses() throws Exception {
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void joinedPagingStableOrderAndSizeCap() throws Exception {
        long c1 = createClassroom(teacherA, "P1" + tag, "P1 " + tag);
        long c2 = createClassroom(teacherA, "P2" + tag, "P2 " + tag);
        long c3 = createClassroom(teacherA, "P3" + tag, "P3 " + tag);
        addMemberDirect(teacherA, c1, learner);
        addMemberDirect(teacherA, c2, learner);
        addMemberDirect(teacherA, c3, learner);

        // Newest membership first (addedAt desc, id desc): c3, c2, c1.
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner))
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].classroomId").value((int) c3))
                .andExpect(jsonPath("$.items[1].classroomId").value((int) c2));
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].classroomId").value((int) c1));
        // Size cap: oversized size is clamped, not an error.
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner))
                        .param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void joinedShowsArchivedStatusAndWorksForRolelessAndDualRoleUsers() throws Exception {
        long classA = createClassroom(teacherA, "AR" + tag, "Arch " + tag);
        long classB = createClassroom(teacherB, "DUAL" + tag, "Dual " + tag);
        addMemberDirect(teacherA, classA, learner);
        addMemberDirect(teacherB, classB, learner);
        jdbc.update("UPDATE classrooms SET status='ARCHIVED' WHERE id=" + classA);

        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.classroomId == " + classA + ")].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.items[?(@.classroomId == " + classB + ")].status").value("ACTIVE"));

        // Roleless member (no user_roles rows): self-scoped read still works.
        long roleless = insertUser("norole" + tag, "No Role " + tag);
        addMemberDirect(teacherA, classA, roleless);
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(roleless)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // Dual-role learner (STUDENT + TEACHER): joined membership intact.
        assignRole(learner, "TEACHER");
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void joinedRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/classrooms/joined")).andExpect(status().isUnauthorized());
    }

    // ============================================================
    // POST /api/classrooms/{id}/leave — direct-add path
    // ============================================================

    @Test
    void leaveDirectAddRemovesMembershipRecordsOptOutAndBlocksAutoRelink() throws Exception {
        long classA = createClassroom(teacherA, "DA" + tag, "Direct " + tag);
        addMemberDirect(teacherA, classA, learner);
        // Sanity: no roster row for the direct add.
        assertThat(rosterCount(classA, "lrnr" + tag + "@t.com")).isZero();

        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());

        // Membership gone → card gone.
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(0));
        // Synthetic DECLINED opt-out row exists for the learner's email.
        assertThat(rosterStatus(classA, "lrnr" + tag + "@t.com")).isEqualTo("DECLINED");

        // A later teacher invitation of the same email must NOT auto-relink.
        JsonNode invitation = addRosterEntry(teacherA, classA,
                "Learner " + tag, "lrnr" + tag + "@t.com");
        assertThat(invitation.get("status").asString()).isEqualTo("PENDING");

        // The learner sees the invitation (enriched with class metadata)…
        MvcResult invites = mockMvc.perform(get("/api/classrooms/roster/invitations")
                        .with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classroomId").value((int) classA))
                .andExpect(jsonPath("$[0].classroomName").value("Direct " + tag))
                .andExpect(jsonPath("$[0].teacherDisplayName").value("Teacher A " + tag))
                .andReturn();
        JsonNode inviteList = objectMapper.readTree(invites.getResponse().getContentAsString());
        long entryId = inviteList.get(0).get("id").asLong();

        // …and explicit accept rejoins exactly once.
        mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/accept").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LINKED"));
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void repeatedDirectAddLeaveCyclesDoNotStackOptOutRows() throws Exception {
        long classA = createClassroom(teacherA, "CY" + tag, "Cycle " + tag);
        // Enroll → leave → re-add → leave: exactly ONE standing DECLINED row.
        addMemberDirect(teacherA, classA, learner);
        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        addMemberDirect(teacherA, classA, learner);
        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());

        assertThat(rosterCount(classA, "lrnr" + tag + "@t.com")).isEqualTo(1);
        assertThat(rosterStatus(classA, "lrnr" + tag + "@t.com")).isEqualTo("DECLINED");
    }

    // ============================================================
    // POST /api/classrooms/{id}/leave — roster-linked path
    // ============================================================

    @Test
    void leaveRosterLinkedMarksEntryDeclinedAndRequiresExplicitAcceptToRejoin() throws Exception {
        long classB = createClassroom(teacherB, "RL" + tag, "Linked " + tag);
        JsonNode linked = addRosterEntry(teacherB, classB, "Learner " + tag, "lrnr" + tag + "@t.com");
        assertThat(linked.get("status").asString()).isEqualTo("LINKED"); // auto-linked (verified)

        mockMvc.perform(post("/api/classrooms/" + classB + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(0));
        // The roster row is DECLINED (audit kept), NOT stale LINKED, and no
        // duplicate opt-out row was added.
        assertThat(rosterStatus(classB, "lrnr" + tag + "@t.com")).isEqualTo("DECLINED");
        assertThat(rosterCount(classB, "lrnr" + tag + "@t.com")).isEqualTo(1);

        // Re-invite stays PENDING; accept is the only relink path.
        JsonNode reinvite = addRosterEntry(teacherB, classB, "Learner " + tag, "lrnr" + tag + "@t.com");
        assertThat(reinvite.get("status").asString()).isEqualTo("PENDING");
        long entryId = reinvite.get("id").asLong();
        mockMvc.perform(post("/api/classrooms/roster/" + entryId + "/accept").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LINKED"));
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].classroomId").value((int) classB));
        // /roster/my (legacy linked projection) mirrors the membership.
        mockMvc.perform(get("/api/classrooms/roster/my").with(jwtFor(learner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].classroomName").value("Linked " + tag));
    }

    @Test
    void leaveArchivedClassroomSucceedsAndRecordsOptOut() throws Exception {
        long classA = createClassroom(teacherA, "AR" + tag, "ArchivedLeave " + tag);
        addMemberDirect(teacherA, classA, learner);
        jdbc.update("UPDATE classrooms SET status='ARCHIVED' WHERE id=" + classA);

        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(rosterStatus(classA, "lrnr" + tag + "@t.com")).isEqualTo("DECLINED");
    }

    // ============================================================
    // Leave: defined error responses + cross-account isolation
    // ============================================================

    @Test
    void leaveRepeatAndNeverMemberReturn404() throws Exception {
        long classA = createClassroom(teacherA, "RP" + tag, "Repeat " + tag);
        addMemberDirect(teacherA, classA, learner);

        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        // Defined repeat-leave response: 404 (no membership).
        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLASSROOM_NOT_FOUND"));
        // Unknown classroom: 404.
        mockMvc.perform(post("/api/classrooms/999999999/leave").with(jwtFor(learner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void leaveByStrangerWhoIsNotAMemberIs404AndDoesNotTouchTheClass() throws Exception {
        long classA = createClassroom(teacherA, "ST" + tag, "Stranger " + tag);
        addMemberDirect(teacherA, classA, learner);
        long stranger = verifiedStudent("strg" + tag, "Stranger " + tag);

        mockMvc.perform(post("/api/classrooms/" + classA + "/leave").with(jwtFor(stranger)))
                .andExpect(status().isNotFound());

        // The member's membership is untouched.
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void leaveRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/classrooms/1/leave")).andExpect(status().isUnauthorized());
    }

    // ============================================================
    // Teacher flows preserved
    // ============================================================

    @Test
    void teacherRemoveDropsJoinedAndTeacherMyUnchanged() throws Exception {
        long classA = createClassroom(teacherA, "RM" + tag, "Remove " + tag);
        long classA2 = createClassroom(teacherA, "RM2" + tag, "Remove2 " + tag);
        createClassroom(teacherB, "RMB" + tag, "OtherTeacher " + tag);
        addMemberDirect(teacherA, classA, learner);

        mockMvc.perform(delete("/api/classrooms/" + classA + "/members/" + learner)
                        .with(jwtFor(teacherA)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/classrooms/joined").with(jwtFor(learner)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // /my remains TEACHER-owned classes only (B's class not in A's list).
        MvcResult my = mockMvc.perform(get("/api/classrooms/my").with(jwtFor(teacherA)))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = objectMapper.readTree(my.getResponse().getContentAsString()).get("items");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        items.forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(classA, classA2);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static JwtRequestPostProcessor jwtFor(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long insertUser(String username, String displayName) {
        return insert("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "')");
    }

    private long verifiedStudent(String username, String displayName) {
        long id = insertUser(username, displayName);
        assignRole(id, "STUDENT");
        jdbc.update("UPDATE users SET email_verified_at=now() WHERE id=" + id);
        return id;
    }

    private void assignRole(long userId, String roleCode) {
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + roleCode + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private long createClassroom(long teacherId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/classrooms").with(jwtFor(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addMemberDirect(long teacherId, long classroomId, long userId) throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroomId + "/members").with(jwtFor(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + userId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
    }

    private JsonNode addRosterEntry(long teacherId, long classroomId, String fullName, String email)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/classrooms/" + classroomId + "/roster")
                        .with(jwtFor(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + fullName + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int rosterCount(long classroomId, String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_roster_entries WHERE classroom_id=" + classroomId
                        + " AND email_normalized='" + email + "'", Integer.class);
        return count == null ? 0 : count;
    }

    private String rosterStatus(long classroomId, String email) {
        return jdbc.queryForObject(
                "SELECT status FROM classroom_roster_entries WHERE classroom_id=" + classroomId
                        + " AND email_normalized='" + email + "'", String.class);
    }
}
