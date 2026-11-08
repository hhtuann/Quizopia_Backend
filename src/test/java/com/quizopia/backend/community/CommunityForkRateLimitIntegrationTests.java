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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §9 rate-limit behavior of the fork endpoint (ADR-006 §9, issue #47):
 * the user bucket (FORK policy, 5/h by production default) saturates
 * with 429 COMMUNITY_RATE_LIMITED + Retry-After. Same-key replays hit
 * the §8 read-only pre-check and do NOT consume admission; every
 * syntactically valid fork request (even one that later 404s) admits
 * FIRST. Runs in a dedicated context ({@code @DirtiesContext})
 * following {@code CommunityBookmarkRateLimitIntegrationTests}.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.fork-limit=5"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityForkRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void forkMutationsSaturatePerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationId = createPublishedPublication(teacherId);
        long forker = newTeacher();

        // Admission 1 (201), then a same-key replay — the §8 read-only
        // pre-check serves it and consumes NOTHING.
        mockMvc.perform(fork(publicationId, forker, "rate-key-0001")).andExpect(status().isCreated());
        mockMvc.perform(fork(publicationId, forker, "rate-key-0001")).andExpect(status().isOk());

        // Admissions 2..5 (201 × 4 with fresh keys).
        for (int i = 2; i <= 5; i++) {
            mockMvc.perform(fork(publicationId, forker, "rate-key-000" + i))
                    .andExpect(status().isCreated());
        }

        // Admission 6 in the same fixed window → 429 + Retry-After + code.
        var denied = mockMvc.perform(fork(publicationId, forker, "rate-key-0006")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // An invisible publication still admits FIRST (§9) → 429, not 404.
        assertThat(mockMvc.perform(fork(9_999_999L, forker, "rate-key-0007")).andReturn()
                .getResponse().getStatus())
                .as("denied requests still consume; the 6th+ admission is 429 whatever the target")
                .isEqualTo(429);

        // A different user has an independent bucket.
        long otherForker = newTeacher();
        mockMvc.perform(fork(publicationId, otherForker, "rate-key-othe1"))
                .andExpect(status().isCreated());
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static RequestBuilder fork(long publicationId, long userId, String idempotencyKey) {
        return post("/api/community/publications/" + publicationId + "/fork")
                .with(jwt().jwt(j -> j.subject(String.valueOf(userId))))
                .header("Idempotency-Key", idempotencyKey);
    }

    private long newTeacher() {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('fkrl%s', 'fkrl%s@t.example.com', 'h', 'RL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(seq, seq++));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                userId);
        return userId;
    }

    private long createPublishedPublication(long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'FKRLE%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, reuse_policy,
                                                    published_at)
                VALUES (%d, %d, 'Rate-limit publication', 'Rl-Alias', 'PUBLISHED', 'VISIBLE',
                        'ALLOW_FORK', now())
                """.formatted(examId, ownerId));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
