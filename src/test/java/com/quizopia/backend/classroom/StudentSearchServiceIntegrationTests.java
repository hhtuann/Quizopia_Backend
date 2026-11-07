package com.quizopia.backend.classroom;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP integration tests for {@code GET /api/students/search} (review F2):
 * TEACHER + CLASSROOM_MEMBER_ADD gating, STUDENT-only result filtering,
 * display-name/username substring matching, the 20-result cap, the ≥2-char
 * minimum (empty list, not an error) and LIKE-wildcard escaping.
 *
 * <p>Uses the {@code jwt()} post-processor with the caller's user id as the
 * subject (same style as {@code AttemptHttpIntegrationTests}); authorization
 * is resolved from the database by {@code StudentSearchService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class StudentSearchServiceIntegrationTests {

    private static final String ENDPOINT = "/api/students/search";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private String tag;
    private long teacherId;

    @BeforeEach
    void setUp() {
        // Unique per-test marker so substring queries only ever match this
        // test's own users (hex chars only — never a LIKE wildcard).
        tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        teacherId = insertUser("tchr" + tag, "Teacher " + tag);
        assignRole(teacherId, "TEACHER");
    }

    // ============================================================
    // 1. Happy path: substring match on display name and username
    // ============================================================

    @Test
    void teacherFindsStudentsByDisplayNameAndUsernameSubstring() throws Exception {
        // Matched through display_name only (username has no tag).
        long byDisplayName = student("plainuser" + tag, "Alicia " + tag);
        // Matched through username only (display_name has no tag).
        long byUsername = student("hunter" + tag, "Bob Plainname");

        MvcResult result = mockMvc.perform(search(teacherId, tag)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].userId").value((int) byDisplayName))
                .andExpect(jsonPath("$.items[0].displayName").value("Alicia " + tag))
                .andExpect(jsonPath("$.items[0].username").value("plainuser" + tag))
                .andExpect(jsonPath("$.items[0].email").value("plainuser" + tag + "@t.com"))
                .andReturn();
        assertThat(returnedIds(result)).containsExactlyInAnyOrder(byDisplayName, byUsername);
    }

    // ============================================================
    // 2. Cap: at most 20 rows
    // ============================================================

    @Test
    void resultsAreCappedAtTwenty() throws Exception {
        Set<Long> seeded = new java.util.HashSet<>();
        for (int i = 1; i <= 25; i++) {
            seeded.add(student("cap" + tag + i, "Cap " + tag + " " + String.format("%02d", i)));
        }
        MvcResult result = mockMvc.perform(search(teacherId, "Cap " + tag)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                // Ordered by display_name ASC → zero-padded seeding makes the
                // first 20 deterministic.
                .andExpect(jsonPath("$.items[0].displayName").value("Cap " + tag + " 01"))
                .andExpect(jsonPath("$.items[19].displayName").value("Cap " + tag + " 20"))
                .andReturn();
        assertThat(returnedIds(result)).isSubsetOf(seeded);
    }

    // ============================================================
    // 2b. Only STUDENT-role users are returned
    // ============================================================

    @Test
    void nonStudentUsersAreExcluded() throws Exception {
        long student1 = student("stu" + tag + "a", "Member " + tag + " One");
        long student2 = student("stu" + tag + "b", "Member " + tag + " Two");
        // A fellow TEACHER and a role-less user whose display names match the
        // query must never leak into learner search results.
        long otherTeacher = insertUser("tchr2" + tag, "Member " + tag + " Teacher");
        assignRole(otherTeacher, "TEACHER");
        long roleless = insertUser("norole" + tag, "Member " + tag + " NoRole");

        MvcResult result = mockMvc.perform(search(teacherId, "Member " + tag)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        assertThat(returnedIds(result)).containsExactlyInAnyOrder(student1, student2)
                .doesNotContain(otherTeacher, roleless);
    }

    // ============================================================
    // 3. Short query: empty list (not an error) for teachers
    // ============================================================

    @Test
    void shortQueryReturnsEmptyListForTeacher() throws Exception {
        // A student that WOULD match a lenient single-char query — proves the
        // minimum length is enforced rather than the query simply not matching.
        student("maria" + tag, "Maria Qaz");

        mockMvc.perform(search(teacherId, "a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(search(teacherId, "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(search(teacherId, "   ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(search(teacherId, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ============================================================
    // 3b. Short (or any) query: 403 for a STUDENT caller
    // ============================================================

    @Test
    void studentCallerIsForbidden() throws Exception {
        long caller = student("callr" + tag, "Caller " + tag);
        // The role gate fires before the length check: even a short query is
        // 403, never the empty-list shape reserved for teachers.
        mockMvc.perform(search(caller, "a")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASSROOM_ACCESS_DENIED"));
        mockMvc.perform(search(caller, tag)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASSROOM_ACCESS_DENIED"));
    }

    // ============================================================
    // 4. TEACHER without CLASSROOM_MEMBER_ADD: 403
    // ============================================================

    @Test
    void teacherWithoutMemberAddPermissionIsForbidden() throws Exception {
        student("stu" + tag + "p", "Perm " + tag);
        int revoked = jdbc.update(
                "DELETE FROM role_permissions rp "
                        + "USING roles r, permissions p "
                        + "WHERE rp.role_id = r.id AND rp.permission_id = p.id "
                        + "AND r.code = 'TEACHER' AND p.code = 'CLASSROOM_MEMBER_ADD'");
        assertThat(revoked).as("CLASSROOM_MEMBER_ADD grant for TEACHER must exist to be revoked")
                .isGreaterThanOrEqualTo(1);

        mockMvc.perform(search(teacherId, tag)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASSROOM_ACCESS_DENIED"));
    }

    // ============================================================
    // 5. LIKE wildcards are matched literally
    // ============================================================

    @Test
    void likeWildcardsAreEscaped() throws Exception {
        long percentNamed = student("pct" + tag, "Wild " + tag + "% Pct");
        long underscoreNamed = student("und" + tag, "Wild " + tag + "_ Und");
        long plainNamed = student("pln" + tag, "Wild " + tag + " Plain");

        // q containing a literal '%' must match ONLY the name that actually
        // contains '%' — the wildcard must not expand to "every student".
        MvcResult percent = mockMvc.perform(search(teacherId, tag + "%")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        assertThat(returnedIds(percent)).containsExactly(percentNamed);

        // q containing a literal '_' must match ONLY the name with a real
        // underscore — '_' must not act as a single-character wildcard.
        MvcResult underscore = mockMvc.perform(search(teacherId, tag + "_")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        assertThat(returnedIds(underscore)).containsExactly(underscoreNamed);

        // Control: the plain-named student is found by the wildcard-free part.
        MvcResult plain = mockMvc.perform(search(teacherId, tag + " Plain")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        assertThat(returnedIds(plain)).containsExactly(plainNamed);
    }

    // ============================================================
    // Gating prerequisite: an unauthenticated call never reaches the service
    // ============================================================

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get(ENDPOINT).param("q", tag)).andExpect(status().isUnauthorized());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private MockHttpServletRequestBuilder search(long callerId, String q) {
        MockHttpServletRequestBuilder request = get(ENDPOINT).with(jwtFor(callerId));
        if (q != null) {
            request = request.param("q", q);
        }
        return request;
    }

    private static JwtRequestPostProcessor jwtFor(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long insertUser(String username, String displayName) {
        return jdbc.queryForObject("INSERT INTO users (username, email, password_hash, display_name) "
                + "VALUES ('" + username + "','" + username + "@t.com','h','" + displayName + "') RETURNING id",
                Long.class);
    }

    private void assignRole(long userId, String roleCode) {
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + roleCode + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
    }

    private long student(String username, String displayName) {
        long userId = insertUser(username, displayName);
        assignRole(userId, "STUDENT");
        return userId;
    }

    private List<Long> returnedIds(MvcResult result) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        List<Long> ids = new ArrayList<>();
        items.forEach(node -> ids.add(node.get("userId").asLong()));
        return ids;
    }
}
