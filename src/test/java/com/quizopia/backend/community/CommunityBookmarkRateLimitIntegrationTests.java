package com.quizopia.backend.community;

import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §9 rate-limit behavior of the bookmark endpoints (ADR-006 §9, issue
 * #45): the user bucket (60/h by policy; 2 in this context) saturates
 * with 429 COMMUNITY_RATE_LIMITED + Retry-After, dedupe saves still
 * consume, DELETE shares the same BOOKMARK policy bucket, and users
 * hold independent buckets. Runs with production-shaped small limits in
 * a dedicated context ({@code @DirtiesContext}) following
 * {@code CommunityReportRateLimitIntegrationTests}.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.bookmark-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityBookmarkRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void bookmarkMutationsSaturatePerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationA = createPublishedPublication(teacherId);
        long publicationB = createPublishedPublication(teacherId);
        long saver = newStudent();

        // Admissions 1..2 pass (limit 2). The second is a DEDUPE 200 —
        // §9: every syntactically valid request admits FIRST, even
        // when the outcome is a dedupe.
        mockMvc.perform(putBookmark(publicationA, saver)).andExpect(status().isCreated());
        mockMvc.perform(putBookmark(publicationA, saver)).andExpect(status().isOk());

        // Admission 3 in the same fixed window → 429 + Retry-After + code.
        var denied = mockMvc.perform(putBookmark(publicationB, saver)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // DELETE shares the BOOKMARK policy bucket: still denied.
        assertThat(mockMvc.perform(deleteBookmark(publicationA, saver)).andReturn()
                .getResponse().getStatus())
                .as("DELETE mutates the same bucket (bookmark 60/h policy)")
                .isEqualTo(429);

        // A different user has an independent bucket.
        long otherSaver = newStudent();
        mockMvc.perform(putBookmark(publicationB, otherSaver)).andExpect(status().isCreated());

        // Reading the shelf is NOT a bookmark mutation — no admission.
        mockMvc.perform(getMyBookmarks(saver)).andExpect(status().isOk());
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder putBookmark(long publicationId, long userId) {
        return put("/api/community/publications/" + publicationId + "/bookmark")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder deleteBookmark(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/bookmark")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder getMyBookmarks(long userId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/community/me/bookmarks")
                .with(jwtSubject(userId));
    }

    private long newStudent() {
        long id = insertUser("brs");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                id);
        return id;
    }

    private long newTeacher() {
        long id = insertUser("brt");
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'BRL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long createPublishedPublication(long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'BRE%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Rate-limit publication', 'RL-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, ownerId));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
