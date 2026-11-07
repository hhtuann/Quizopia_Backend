package com.quizopia.backend.community;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anonymous public-read rate limiting (ADR-006 §9, issue #41): the four
 * public GET routes admit against the {@code ip:<servlet-remote-addr>}
 * bucket (PUBLIC_READ_IP policy) through the limiter's SELF-COMMITTING
 * admission path — no surrounding transaction exists on these reads, so
 * anonymous access never depends on one. Saturation → 429
 * COMMUNITY_RATE_LIMITED + {@code Retry-After}; buckets are per-IP; an
 * authenticated caller is never IP-bucketed (user bucket only).
 *
 * <p>Dedicated context with a tiny production-shaped threshold
 * ({@code @DirtiesContext}), mirroring
 * {@code CommunityRateLimiterIntegrationTests}; not {@code @Transactional}
 * because admission commits its own transaction.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.public-read-ip-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityPublicReadRateLimitIntegrationTests {

    private static final String CATALOG = "/api/public/community/publications";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    @Test
    void anonymousReadsSaturateWith429AndRetryAfterPerIp() throws Exception {
        createPublishedPublication();

        String ip = "203.0.113." + (seq++ % 254);
        mockMvc.perform(get(CATALOG).with(remoteAddr(ip)))
                .andExpect(status().isOk());
        mockMvc.perform(get(CATALOG).with(remoteAddr(ip)))
                .andExpect(status().isOk());

        MvcResult denied = mockMvc.perform(get(CATALOG).with(remoteAddr(ip))).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(denied.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 3600L);
        assertThat(denied.getResponse().getContentAsString())
                .contains("COMMUNITY_RATE_LIMITED");

        // Independent bucket per IP; admission is the self-committing path.
        mockMvc.perform(get(CATALOG).with(remoteAddr("198.51.100.77")))
                .andExpect(status().isOk());

        // The other anonymous routes share the same per-IP admission.
        MvcResult tagsDenied = mockMvc.perform(
                        get("/api/public/community/tags").with(remoteAddr(ip))).andReturn();
        assertThat(tagsDenied.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void authenticatedCallersAreNeverIpBucketedOnPublicRoutes() throws Exception {
        createPublishedPublication();

        String sharedIp = "192.0.2." + (seq++ % 254);
        long userId = 8_800_000_000L + (seq++ % 1_000_000);
        // Saturate the IP bucket with anonymous calls…
        mockMvc.perform(get(CATALOG).with(remoteAddr(sharedIp)))
                .andExpect(status().isOk());
        mockMvc.perform(get(CATALOG).with(remoteAddr(sharedIp)))
                .andExpect(status().isOk());
        // …yet the same address WITH a valid JWT still reads (user bucket,
        // separate dimension and separate bucket — §9).
        mockMvc.perform(get(CATALOG).with(remoteAddr(sharedIp)).with(jwtSubject(userId)))
                .andExpect(status().isOk());
        mockMvc.perform(get(CATALOG).with(remoteAddr(sharedIp)).with(jwtSubject(userId + 1)))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // fixtures (#40 patterns; the publication is real and visible)
    // ==================================================================

    private void createPublishedPublication() throws Exception {
        long teacherId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('prl%s', 'prl%s@t.example.com', 'h', 'RL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(seq, seq));
        seq++;
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                teacherId);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RLPX%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        MvcResult created = mockMvc.perform(post("/api/community/publications")
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"t\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long publicationId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created.getResponse().getContentAsString())
                .get("publicationId").asLong();
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"authorAlias\":\"Sensei\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
