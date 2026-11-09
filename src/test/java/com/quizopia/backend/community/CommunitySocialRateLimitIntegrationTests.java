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
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5 rate-limit behavior of the #65 social endpoints (ADR-007 §5): the
 * LIKE bucket (60/h by policy; 2 in this context) and the RATING bucket
 * (30/h by policy; 2 in this context) saturate with 429
 * COMMUNITY_RATE_LIMITED + Retry-After; idempotent re-PUTs and rating
 * updates still consume; DELETE shares its policy's bucket; users hold
 * independent buckets; and reads (either social-summary twin) never
 * consume a LIKE/RATING bucket. Runs with production-shaped small
 * limits in a dedicated context ({@code @DirtiesContext}) following
 * {@code CommunityBookmarkRateLimitIntegrationTests}.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.like-limit=2",
        "quizopia.community.rate-limit.rating-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunitySocialRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void likeMutationsSaturatePerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationA = createPublishedPublication(teacherId);
        long publicationB = createPublishedPublication(teacherId);
        long liker = newStudent();

        // Admissions 1..2 pass (limit 2). The second is a DEDUPE 200 —
        // §5: every syntactically valid request admits FIRST, even
        // when the outcome is a dedupe.
        mockMvc.perform(putLike(publicationA, liker)).andExpect(status().isCreated());
        mockMvc.perform(putLike(publicationA, liker)).andExpect(status().isOk());

        // Admission 3 in the same fixed window → 429 + Retry-After + code.
        var denied = mockMvc.perform(putLike(publicationB, liker)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // DELETE shares the LIKE policy bucket: still denied.
        assertThat(mockMvc.perform(deleteLike(publicationA, liker)).andReturn()
                .getResponse().getStatus())
                .as("DELETE mutates the same bucket (like 60/h policy)")
                .isEqualTo(429);

        // A different user has an independent bucket.
        long otherLiker = newStudent();
        mockMvc.perform(putLike(publicationB, otherLiker)).andExpect(status().isCreated());

        // Reads are NOT like mutations: the summaries never consume the
        // LIKE bucket (the twins admit under PUBLIC_READ_IP instead).
        mockMvc.perform(get("/api/community/publications/" + publicationA + "/social-summary")
                        .with(jwtSubject(liker)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/community/publications/" + publicationA + "/social-summary"))
                .andExpect(status().isOk());
    }

    @Test
    void ratingMutationsSaturatePerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationA = createPublishedPublication(teacherId);
        long publicationB = createPublishedPublication(teacherId);
        long rater = newStudent();

        // Admissions 1..2 pass (limit 2): the first rates, the second
        // UPDATES the same row (§5: updates consume too).
        mockMvc.perform(putRating(publicationA, rater, 3)).andExpect(status().isCreated());
        mockMvc.perform(putRating(publicationA, rater, 5)).andExpect(status().isOk());

        // Admission 3 in the same fixed window → 429 + Retry-After.
        var denied = mockMvc.perform(putRating(publicationB, rater, 4)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // DELETE shares the RATING policy bucket: still denied.
        assertThat(mockMvc.perform(deleteRating(publicationA, rater)).andReturn()
                .getResponse().getStatus())
                .as("DELETE mutates the same bucket (rating 30/h policy)")
                .isEqualTo(429);

        // A different user has an independent bucket — and the LIKE
        // policy is a SEPARATE bucket from RATING (the policy segment
        // in the HMAC key): the saturated rater can still like.
        long otherRater = newStudent();
        mockMvc.perform(putRating(publicationB, otherRater, 4)).andExpect(status().isCreated());
        mockMvc.perform(putLike(publicationA, rater)).andExpect(status().isCreated());
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder putLike(long publicationId, long userId) {
        return put("/api/community/publications/" + publicationId + "/like")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder deleteLike(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/like")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder putRating(long publicationId, long userId, int rating) {
        return put("/api/community/publications/" + publicationId + "/rating")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":" + rating + "}");
    }

    private static RequestBuilder deleteRating(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/rating")
                .with(jwtSubject(userId));
    }

    private long newStudent() {
        long id = insertUser("sors");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                id);
        return id;
    }

    private long newTeacher() {
        long id = insertUser("sort");
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SRL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long createPublishedPublication(long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'SORX%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Rate-limit publication', 'SRL-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, ownerId));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
