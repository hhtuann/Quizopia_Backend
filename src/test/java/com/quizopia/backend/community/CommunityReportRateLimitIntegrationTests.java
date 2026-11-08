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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §9 rate-limit behavior of the report endpoint (issue #42): the user
 * bucket (5/h by policy; 2 in this context) saturates with 429
 * COMMUNITY_RATE_LIMITED + Retry-After, the IP bucket (20/h by policy;
 * 3 here) is an INDEPENDENT threshold keyed by servlet remote addr
 * (never forwarded headers), and denied requests still consume
 * attempts. Runs with production-shaped small limits in a dedicated
 * context ({@code @DirtiesContext}) following
 * {@code CommunityRateLimiterIntegrationTests}.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.report-user-limit=2",
        "quizopia.community.rate-limit.report-ip-limit=3"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityReportRateLimitIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    // ==================================================================
    // user bucket saturation
    // ==================================================================

    @Test
    void reportEndpointSaturatesPerUserWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationA = createPublishedPublication(teacherId);
        long publicationB = createPublishedPublication(teacherId);
        long publicationC = createPublishedPublication(teacherId);
        long reporter = newStudent();

        // Admissions 1..2 pass (limit 2). The second is a DEDUPE 200 —
        // §9: every syntactically valid request admits FIRST, even when
        // the outcome is a dedupe or 404.
        mockMvc.perform(report(publicationA, reporter)).andExpect(status().isCreated());
        mockMvc.perform(report(publicationA, reporter)).andExpect(status().isOk());

        // Admission 3 in the same fixed window → 429 + Retry-After + code.
        var denied = mockMvc.perform(report(publicationB, reporter)).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // A different reporter has an independent USER bucket.
        long otherReporter = newStudent();
        mockMvc.perform(report(publicationB, otherReporter)).andExpect(status().isCreated());

        // Still denied for the first reporter, on a different target.
        assertThat(mockMvc.perform(report(publicationC, reporter)).andReturn()
                .getResponse().getStatus()).isEqualTo(429);
    }

    // ==================================================================
    // IP bucket is independent (remote addr keyed, headers never read)
    // ==================================================================

    @Test
    void ipBucketIsIndependentOfTheUserBucket() throws Exception {
        long teacherId = newTeacher();
        long publication = createPublishedPublication(teacherId);
        String sharedIp = "198.51.100.7";

        // report-ip-limit = 3: three DISTINCT users from the SAME IP
        // pass; the FOURTH distinct user trips the IP bucket with 429.
        long reporterA = newStudent();
        long reporterB = newStudent();
        long reporterC = newStudent();
        long reporterD = newStudent();

        mockMvc.perform(report(publication, reporterA, sharedIp)).andExpect(status().isCreated());
        mockMvc.perform(report(publication, reporterB, sharedIp)).andExpect(status().isCreated());
        mockMvc.perform(report(publication, reporterC, sharedIp)).andExpect(status().isCreated());

        var ipDenied = mockMvc.perform(report(publication, reporterD, sharedIp)).andReturn();
        assertThat(ipDenied.getResponse().getStatus())
                .as("fourth distinct user from the same IP trips the IP bucket")
                .isEqualTo(429);

        // The same fourth user from a DIFFERENT remote addr is fine —
        // the IP bucket keys per address, not per user.
        mockMvc.perform(report(publication, reporterD, "198.51.100.8"))
                .andExpect(status().isCreated());

        // Forwarded headers are ignored: X-Forwarded-For cannot buy
        // admissions (V23 posture).
        var forwarded = mockMvc.perform(
                        post("/api/community/publications/" + publication + "/reports")
                                .with(jwtSubject(reporterD))
                                .with(remoteAddr("198.51.100.7"))
                                .header("X-Forwarded-For", "203.0.113.99")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reasonCode\":\"SPAM\"}"))
                .andReturn();
        assertThat(forwarded.getResponse().getStatus())
                .as("forwarded headers never key the IP bucket")
                .isEqualTo(429);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestPostProcessor remoteAddr(String addr) {
        return request -> {
            request.setRemoteAddr(addr);
            return request;
        };
    }

    private static RequestBuilder report(long publicationId, long reporterId) {
        return report(publicationId, reporterId, "127.0.0.1");
    }

    private static RequestBuilder report(long publicationId, long reporterId, String addr) {
        return post("/api/community/publications/" + publicationId + "/reports")
                .with(jwtSubject(reporterId))
                .with(remoteAddr(addr))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"SPAM\"}");
    }

    private long newStudent() {
        long id = insertUser("rls");
        jdbc.update(
                "INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'STUDENT'",
                id);
        return id;
    }

    private long newTeacher() {
        long id = insertUser("rlt");
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'RRL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private long createPublishedPublication(long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RRE%s', 'Rate-limit exam', 'PUBLISHED', now(), 1)
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
