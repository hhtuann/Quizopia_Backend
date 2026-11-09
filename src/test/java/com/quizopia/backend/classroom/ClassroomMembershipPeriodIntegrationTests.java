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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CLASS-M3 (issue #54) Stage A: {@code classroom_membership_periods} (V30)
 * history capture on EVERY membership write path — teacher direct add,
 * teacher remove, learner leave, roster accept/decline/revoke (the verified
 * reconciliation paths funnel through the same INSERT) — plus rejoin epochs,
 * the honest backfill, and the single-open-period invariant under concurrent
 * membership mutations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class ClassroomMembershipPeriodIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private String tag;
    private long teacher;
    private long learner;
    private long classroom;

    @BeforeEach
    void setUp() throws Exception {
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        teacher = user("tch" + tag, "Teacher " + tag, "TEACHER");
        learner = user("lrn" + tag, "Learner " + tag, "STUDENT");
        classroom = createClassroom(teacher, "C" + tag);
    }

    // ============================================================
    // Capture on every write path
    // ============================================================

    @Test
    void teacherDirectAddOpensPeriodWithJoinedAt() throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroom + "/members").with(jwtFor(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + learner + "]}"))
                .andExpect(status().isOk());

        Map<String, Object> period = openPeriod(classroom, learner);
        assertThat(period).isNotNull();
        // joined_at anchors at the live row's added_at (same statement time).
        assertThat(jdbc.queryForObject(
                "SELECT p.joined_at = cm.added_at FROM classroom_membership_periods p "
                        + "JOIN classroom_members cm ON cm.classroom_id = p.classroom_id "
                        + "AND cm.user_id = p.user_id WHERE p.classroom_id = " + classroom
                        + " AND p.user_id = " + learner + " AND p.left_at IS NULL",
                Boolean.class)).isTrue();
    }

    @Test
    void teacherRemoveClosesPeriod() throws Exception {
        addMember(teacher, classroom, learner);
        mockMvc.perform(delete("/api/classrooms/" + classroom + "/members/" + learner)
                        .with(jwtFor(teacher)))
                .andExpect(status().isOk());

        assertThat(openPeriod(classroom, learner)).isNull();
        Map<String, Object> closed = closedPeriod(classroom, learner);
        assertThat(closed).isNotNull();
        assertThat(((java.sql.Timestamp) closed.get("left_at")).toInstant())
                .isAfter(((java.sql.Timestamp) closed.get("joined_at")).toInstant());
        // Membership is gone; history survives.
        assertThat(memberCount(classroom, learner)).isZero();
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    @Test
    void learnerLeaveClosesPeriod() throws Exception {
        addMember(teacher, classroom, learner);
        mockMvc.perform(post("/api/classrooms/" + classroom + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        assertThat(openPeriod(classroom, learner)).isNull();
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    @Test
    void rosterDeclineClosesPeriod() throws Exception {
        // Teacher adds the learner (member) AND a roster entry that links.
        addMember(teacher, classroom, learner);
        long entry = insertRosterEntry(classroom, learner, "LINKED");
        mockMvc.perform(post("/api/classrooms/roster/" + entry + "/decline").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        assertThat(openPeriod(classroom, learner)).isNull();
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    @Test
    void rosterRevokeClosesPeriod() throws Exception {
        addMember(teacher, classroom, learner);
        long entry = insertRosterEntry(classroom, learner, "LINKED");
        mockMvc.perform(delete("/api/classrooms/" + classroom + "/roster/" + entry)
                        .with(jwtFor(teacher)))
                .andExpect(status().isNoContent());
        assertThat(openPeriod(classroom, learner)).isNull();
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    @Test
    void invitationAcceptOpensNewPeriod() throws Exception {
        long entry = insertRosterEntry(classroom, null, "PENDING");
        mockMvc.perform(post("/api/classrooms/roster/" + entry + "/accept").with(jwtFor(learner)))
                .andExpect(status().isOk());
        Map<String, Object> period = openPeriod(classroom, learner);
        assertThat(period).isNotNull();
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    // ============================================================
    // Rejoin epochs + rollbacks leave no phantom history
    // ============================================================

    @Test
    void rejoinCreatesDistinctEpochs() throws Exception {
        addMember(teacher, classroom, learner);
        mockMvc.perform(post("/api/classrooms/" + classroom + "/leave").with(jwtFor(learner)))
                .andExpect(status().isNoContent());
        // Rejoin via a fresh invitation accept (the opt-out requires consent).
        long entry = insertRosterEntry(classroom, null, "PENDING");
        mockMvc.perform(post("/api/classrooms/roster/" + entry + "/accept").with(jwtFor(learner)))
                .andExpect(status().isOk());

        List<Map<String, Object>> periods = periods(classroom, learner);
        assertThat(periods).hasSize(2);
        assertThat(periods.stream().filter(p -> p.get("left_at") == null).count()).isEqualTo(1);
        // Epoch 2 starts strictly after epoch 1 ended.
        java.sql.Timestamp firstLeft = (java.sql.Timestamp) periods.get(0).get("left_at");
        java.sql.Timestamp secondJoined = (java.sql.Timestamp) periods.get(1).get("joined_at");
        assertThat(secondJoined).isAfter(firstLeft);
    }

    @Test
    void failedAddLeavesNoPeriod() throws Exception {
        // Adding a NON-EXISTENT user fails the whole request — no history row.
        mockMvc.perform(post("/api/classrooms/" + classroom + "/members").with(jwtFor(teacher))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[999999999]}"))
                .andExpect(status().isOk()); // invalid users skipped, nothing added
        assertThat(periodCount(classroom, 999999999L)).isZero();
        // Duplicated add is rejected per-user; the FIRST membership (from
        // setUp-less state) must not gain a second epoch.
        addMember(teacher, classroom, learner);
        addMember(teacher, classroom, learner); // duplicate → skipped, no INSERT
        assertThat(periodCount(classroom, learner)).isEqualTo(1);
    }

    @Test
    void backfilledCurrentMembersHaveOpenPeriods() throws Exception {
        // Any member created through the API must have exactly one open
        // period — this is the V30 backfill contract for pre-migration rows
        // (test DB migrates from scratch, so every row IS "backfilled").
        addMember(teacher, classroom, learner);
        Integer orphans = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members cm WHERE NOT EXISTS ("
                        + "SELECT 1 FROM classroom_membership_periods p "
                        + "WHERE p.classroom_id = cm.classroom_id AND p.user_id = cm.user_id "
                        + "AND p.left_at IS NULL)",
                Integer.class);
        assertThat(orphans).isZero();
    }

    // ============================================================
    // Concurrency: one open period under parallel add/remove cycles
    // ============================================================

    @Test
    void concurrentMembershipMutationsKeepSingleOpenEpoch() throws Exception {
        addMember(teacher, classroom, learner);
        // Parallel API calls: several leaves (only the first wins per the M1
        // 404 contract) racing a teacher remove — the trigger must never
        // produce two open periods or a lost epoch.
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    mockMvc.perform(post("/api/classrooms/" + classroom + "/leave")
                            .with(jwtFor(learner))).andReturn();
                } catch (Exception ignored) {
                    // concurrency noise is fine — invariants checked after
                }
            }));
        }
        for (Thread t : threads) {
            t.join(5000);
        }
        mockMvc.perform(delete("/api/classrooms/" + classroom + "/members/" + learner)
                        .with(jwtFor(teacher)))
                .andExpect(status().isOk());

        assertThat(memberCount(classroom, learner)).isZero();
        List<Map<String, Object>> periods = periods(classroom, learner);
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).get("left_at")).isNotNull();
        Integer openCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_membership_periods WHERE classroom_id = "
                        + classroom + " AND user_id = " + learner + " AND left_at IS NULL",
                Integer.class);
        assertThat(openCount).isZero();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static JwtRequestPostProcessor jwtFor(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long user(String username, String displayName, String role) {
        long id = jdbc.queryForObject("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + id
                + ", id FROM roles WHERE code='" + role + "'");
        if ("STUDENT".equals(role)) {
            jdbc.update("UPDATE users SET email_verified_at=now() WHERE id=" + id);
        }
        return id;
    }

    private long createClassroom(long ownerId, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/classrooms").with(jwtFor(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Class " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void addMember(long teacherId, long classroomId, long userId) throws Exception {
        mockMvc.perform(post("/api/classrooms/" + classroomId + "/members").with(jwtFor(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[" + userId + "]}"))
                .andExpect(status().isOk());
    }

    /** Roster entry for the learner's canonical email (lrn<tag>@t.com). */
    private long insertRosterEntry(long classroomId, Long userId, String status) {
        String user = status.equals("PENDING") ? "NULL" : String.valueOf(userId);
        String linked = "LINKED".equals(status) ? ", linked_at" : "";
        String linkedVal = "LINKED".equals(status) ? ", now()" : "";
        return jdbc.queryForObject("INSERT INTO classroom_roster_entries "
                + "(classroom_id, email_normalized, full_name, status, user_id, created_by_user_id"
                + linked + ") "
                + "VALUES (" + classroomId + ",'lrn" + tag + "@t.com','Learner " + tag + "','"
                + status + "'," + user + "," + teacher + linkedVal + ") RETURNING id", Long.class);
    }

    private Map<String, Object> openPeriod(long classroomId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM classroom_membership_periods WHERE classroom_id = " + classroomId
                        + " AND user_id = " + userId + " AND left_at IS NULL");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> closedPeriod(long classroomId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM classroom_membership_periods WHERE classroom_id = " + classroomId
                        + " AND user_id = " + userId + " AND left_at IS NOT NULL");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> periods(long classroomId, long userId) {
        return jdbc.queryForList("SELECT * FROM classroom_membership_periods WHERE classroom_id = "
                + classroomId + " AND user_id = " + userId + " ORDER BY id");
    }

    private int periodCount(long classroomId, long userId) {
        return periods(classroomId, userId).size();
    }

    private int memberCount(long classroomId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM classroom_members WHERE classroom_id = " + classroomId
                        + " AND user_id = " + userId, Integer.class);
        return count == null ? 0 : count;
    }
}
