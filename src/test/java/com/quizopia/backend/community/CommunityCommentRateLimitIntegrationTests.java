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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5 rate-limit behavior of the #66 comment endpoints (ADR-007): the
 * COMMENT_CREATE (10/h; 2 here), COMMENT_EDIT (20/h; 3) and
 * COMMENT_DELETE (30/h; 3) buckets saturate independently with 429
 * COMMUNITY_RATE_LIMITED + Retry-After; the COMMENT_REPORT pair
 * (5/h user, 20/h IP; 2/2 here) is separate BOTH from the comment
 * mutation buckets AND from the publication REPORT_* buckets (a
 * saturated comment-report budget never touches a publication report,
 * and vice versa); and the public comments page never consumes any
 * comment mutation bucket. Runs with production-shaped small limits in
 * a dedicated context ({@code @DirtiesContext}).
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.comment-create-limit=2",
        "quizopia.community.rate-limit.comment-edit-limit=3",
        "quizopia.community.rate-limit.comment-delete-limit=3",
        "quizopia.community.rate-limit.comment-report-user-limit=2",
        "quizopia.community.rate-limit.comment-report-ip-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityCommentRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void createEditAndDeleteSaturateTheirOwnBucketsIndependently() throws Exception {
        long teacherId = newTeacher();
        long publicationId = createPublishedPublication(teacherId);
        long commenter = newStudent();

        // CREATE: admissions 1..2 pass; 3 → 429 + Retry-After.
        long c1 = createComment(publicationId, commenter, "one");
        createComment(publicationId, commenter, "two");
        var deniedCreate = mockMvc.perform(postComment(publicationId, commenter, "three"))
                .andReturn().getResponse();
        assertThat(deniedCreate.getStatus()).isEqualTo(429);
        assertThat(deniedCreate.getHeader("Retry-After")).isNotNull();
        assertThat(deniedCreate.getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // EDIT lives in its own bucket: still admitted (3 allowed).
        assertThat(mockMvc.perform(patchComment(c1, commenter, "edited"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(patchComment(c1, commenter, "edited again"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(patchComment(c1, commenter, "last edit"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(patchComment(c1, commenter, "over the cap"))
                .andReturn().getResponse().getStatus()).isEqualTo(429);

        // DELETE in its own bucket too: still admitted (3 allowed).
        assertThat(mockMvc.perform(deleteComment(c1, commenter))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(deleteComment(c1, commenter))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(deleteComment(c1, commenter))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(deleteComment(c1, commenter))
                .andReturn().getResponse().getStatus()).isEqualTo(429);

        // The PUBLIC comments page never consumes a mutation bucket.
        assertThat(mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/comments"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // A different user has independent buckets.
        long other = newStudent();
        assertThat(mockMvc.perform(postComment(publicationId, other, "other user"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void commentReportBudgetsAreSeparateFromEachOtherAndFromPublicationReports() throws Exception {
        long teacherId = newTeacher();
        long publicationId = createPublishedPublication(teacherId);
        long reporter = newStudent();
        long comment = createComment(publicationId, teacherId, "target");

        // COMMENT_REPORT_USER=2: two reports on two comments pass…
        long comment2 = createComment(publicationId, teacherId, "target two");
        mockMvc.perform(reportComment(comment, reporter)).andExpect(status().isCreated());
        mockMvc.perform(reportComment(comment2, reporter)).andExpect(status().isCreated());
        // …the third comment report in the window → 429.
        long comment3 = createComment(publicationId, newStudent(), "target three");
        assertThat(mockMvc.perform(reportComment(comment3, reporter))
                .andReturn().getResponse().getStatus()).isEqualTo(429);

        // …but PUBLICATION reports (REPORT_USER policy, separate budget)
        // are still admitted for the same saturated reporter.
        assertThat(mockMvc.perform(post("/api/community/publications/" + publicationId
                        + "/reports").with(jwtSubject(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\"}"))
                .andReturn().getResponse().getStatus())
                .as("publication REPORT_USER budget is independent of COMMENT_REPORT_USER")
                .isEqualTo(201);

        // And the IP bucket is its own dimension: a DIFFERENT reporter
        // from the same MockMvc remote address (127.0.0.1) is IP-capped
        // after the shared 2, even though her user bucket is empty.
        long secondReporter = newStudent();
        assertThat(mockMvc.perform(reportComment(comment3, secondReporter))
                .andReturn().getResponse().getStatus())
                .as("COMMENT_REPORT_IP is a shared, separate bucket (2/h here)")
                .isEqualTo(429);

        // A different user's comment mutations are untouched by all of it.
        assertThat(mockMvc.perform(postComment(publicationId, secondReporter, "still fine"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static org.springframework.test.web.servlet.RequestBuilder postComment(
            long publicationId, long userId, String body) {
        return post("/api/community/publications/" + publicationId + "/comments")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\",\"alias\":\"Rl-" + userId + "\"}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder patchComment(
            long commentId, long userId, String body) {
        return patch("/api/community/comments/" + commentId)
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"" + body + "\"}");
    }

    private static org.springframework.test.web.servlet.RequestBuilder deleteComment(
            long commentId, long userId) {
        return delete("/api/community/comments/" + commentId).with(jwtSubject(userId));
    }

    private static org.springframework.test.web.servlet.RequestBuilder reportComment(
            long commentId, long userId) {
        return post("/api/community/comments/" + commentId + "/reports")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"SPAM\"}");
    }

    private long createComment(long publicationId, long userId, String body) throws Exception {
        MvcResult result = mockMvc.perform(postComment(publicationId, userId, body))
                .andExpect(status().isCreated()).andReturn();
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get("commentId").asLong();
    }

    private long newTeacher() {
        return newUser("rlt", "TEACHER");
    }

    private long newStudent() {
        return newUser("rls", "STUDENT");
    }

    private long newUser(String prefix, String role) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'RL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix + System.nanoTime(), prefix, prefix, System.nanoTime(),
                System.nanoTime()));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private long createPublishedPublication(long teacherId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RLX%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Rate-limit publication', 'Rl-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    // Non-@Transactional suite: committed fixtures would leak into the
    // shared container and break the exact-count catalog suites (which
    // run earlier in the default run order). Only the rows this class
    // created (created_at >= class load) are removed; publication
    // children CASCADE along.
    private static final java.time.Instant CLASS_START = java.time.Instant.now();

    @org.junit.jupiter.api.AfterEach
    void cleanCommittedFixtures() {
        jdbc.update("DELETE FROM community_publications WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
        jdbc.update("DELETE FROM community_member_profiles WHERE created_at >= CAST(? AS timestamptz)", CLASS_START);
    }

}
