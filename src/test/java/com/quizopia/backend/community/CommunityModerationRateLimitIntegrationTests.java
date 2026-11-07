package com.quizopia.backend.community;

import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §9 rate-limit behavior of the moderation writes (ADR-006 §4/§9, issue
 * #42): hide, restore, dismiss and resolve all admit FIRST against the
 * caller's MODERATION user bucket (60/h by policy; 2 in this context) —
 * a compromised moderator cannot flood {@code
 * community_moderation_actions}. Saturation yields 429
 * COMMUNITY_RATE_LIMITED + Retry-After, the four writes share ONE
 * bucket, and read-only moderation routes never admit. Runs with
 * production-shaped small limits in a dedicated context ({@code
 * @DirtiesContext}) following {@code
 * CommunityBookmarkRateLimitIntegrationTests}.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.moderation-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityModerationRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void moderationWritesSaturatePerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationId = createPublishedPublication(teacherId);
        long moderatorId = newModerator();

        // Admissions 1..2 pass (limit 2): a real hide + its restore.
        mockMvc.perform(hide(publicationId, moderatorId)).andExpect(status().isOk());
        mockMvc.perform(restore(publicationId, moderatorId)).andExpect(status().isOk());

        // Admission 3 in the same fixed window → 429 + Retry-After + code,
        // and the error body is uncached (Cache-Control: no-store).
        var denied = mockMvc.perform(hide(publicationId, moderatorId)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());
        assertThat(moderationState(publicationId)).isEqualTo("VISIBLE");

        // All four moderation writes share the ONE MODERATION bucket:
        // restore and the report dismiss/resolve paths are still denied.
        assertThat(mockMvc.perform(restore(publicationId, moderatorId)).andReturn()
                .getResponse().getStatus())
                .as("restore shares the MODERATION policy bucket")
                .isEqualTo(429);

        long reportId = submitReport(newStudent(), publicationId);
        assertThat(mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/dismiss")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"nope\"}"))
                        .andReturn().getResponse().getStatus())
                .as("dismiss shares the MODERATION policy bucket")
                .isEqualTo(429);
        assertThat(reportStatus(reportId)).isEqualTo("OPEN");
        assertThat(mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/resolve")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"handled\"}"))
                        .andReturn().getResponse().getStatus())
                .as("resolve shares the MODERATION policy bucket")
                .isEqualTo(429);
        assertThat(reportStatus(reportId)).isEqualTo("OPEN");

        // Reads never admit — the queue stays reachable after saturation.
        mockMvc.perform(get("/api/community/moderation/reports")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk());

        // A different moderator has an independent bucket.
        long otherModerator = newModerator();
        mockMvc.perform(hide(publicationId, otherModerator)).andExpect(status().isOk());
        assertThat(moderationState(publicationId)).isEqualTo("HIDDEN");
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static org.springframework.test.web.servlet.RequestBuilder hide(
            long publicationId, long moderatorId) {
        return post("/api/community/moderation/publications/" + publicationId + "/hide")
                .with(jwtSubject(moderatorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"spam wave\"}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder restore(
            long publicationId, long moderatorId) {
        return post("/api/community/moderation/publications/" + publicationId + "/restore")
                .with(jwtSubject(moderatorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"false positive\"}");
    }

    private long submitReport(long reporterId, long publicationId) throws Exception {
        String body = mockMvc.perform(
                        post("/api/community/publications/" + publicationId + "/reports")
                                .with(jwtSubject(reporterId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reasonCode\":\"SPAM\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("reportId").asLong();
    }

    private String moderationState(long publicationId) {
        return jdbc.queryForObject(
                "SELECT moderation_state FROM community_publications WHERE id = ?",
                String.class, publicationId);
    }

    private String reportStatus(long reportId) {
        return jdbc.queryForObject(
                "SELECT status FROM community_reports WHERE id = ?", String.class, reportId);
    }

    private long newModerator() {
        // Dual-role moderator is fine (§5): the grant is what authorizes.
        long id = insertUser("mrm");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                id);
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'COMMUNITY_MODERATOR'",
                id);
        return id;
    }

    private long newStudent() {
        long id = insertUser("mrs");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                id);
        return id;
    }

    private long newTeacher() {
        long id = insertUser("mrt");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                id);
        return id;
    }

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'MRL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long createPublishedPublication(long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'MRE%s', 'Moderation rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Moderation rate-limit publication', 'MRL-Alias',
                        'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, ownerId));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
