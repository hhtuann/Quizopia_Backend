package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityRateLimiter;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code CommunityRateLimiter} behavior (ADR-006 §9, issue #40): fixed
 * 1-hour windows saturate at the configured threshold and deny with 429
 * COMMUNITY_RATE_LIMITED + Retry-After; user and IP buckets key
 * independently; admission FAILS CLOSED when the bucket store is
 * unavailable (both at the component level and through the HTTP route);
 * bucket keys are HMAC digests (64 hex, no plaintext identity).
 *
 * <p>Runs with production-shaped small limits in a dedicated context
 * ({@code @DirtiesContext}) so the shared-test-context high limits are
 * not disturbed.
 */
@SpringBootTest(properties = {
        "quizopia.community.rate-limit.publish-unpublish-limit=2",
        "quizopia.community.rate-limit.report-ip-limit=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityRateLimiterIntegrationTests {

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityRateLimiter rateLimiter;
    @Autowired
    private TransactionTemplate txTemplate;

    private static long seq = System.nanoTime();

    // ==================================================================
    // window saturation over HTTP
    // ==================================================================

    @Test
    void publishEndpointSaturatesWith429AndRetryAfter() throws Exception {
        long teacherId = newTeacher();
        long publicationId = createPublishedPublication(teacherId);

        // Admission 1 and 2 pass (limit 2) — the second is an idempotent
        // no-op publish that is still admitted (§9: every syntactically
        // valid write transaction admits first).
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        // Third admission in the window → 429 + Retry-After + code.
        org.springframework.test.web.servlet.MvcResult denied = mockMvc.perform(
                        post("/api/community/publications/" + publicationId + "/publish")
                                .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);
        assertThat(denied.getResponse().getHeader("Retry-After")).isNotNull();
        long retryAfter = Long.parseLong(denied.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).as("Retry-After points at the 1h fixed-window boundary")
                .isBetween(1L, 3600L);
        assertThat(denied.getResponse().getContentAsString())
                .contains(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());

        // Denied attempts keep counting but a different user has an
        // independent bucket.
        long otherTeacher = newTeacher();
        long otherPublication = createPublishedPublication(otherTeacher);
        int other = mockMvc.perform(post("/api/community/publications/" + otherPublication + "/publish")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(otherTeacher)))))
                .andReturn().getResponse().getStatus();
        assertThat(other).isEqualTo(200);
    }

    // ==================================================================
    // component-level buckets
    // ==================================================================

    @Test
    void userAndIpBucketsAreIndependentAndKeyedWithoutPlaintext() {
        long userA = 9_000_000_000L + (seq++ % 1_000_000);
        long userB = userA + 1;
        String ip = "203.0.113.9";

        // User bucket saturation (limit 2).
        admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA);
        admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA);
        assertThatThrownBy(() -> admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA))
                .isInstanceOf(CommunityException.class)
                .extracting(ex -> ((CommunityException) ex).getErrorCode().code())
                .isEqualTo(CommunityErrorCode.COMMUNITY_RATE_LIMITED.code());
        // Independent bucket for another user.
        admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userB);

        // IP bucket: separate rows, separate threshold source.
        admitIpOk(CommunityRateLimiter.Policy.REPORT_IP, ip);
        admitIpOk(CommunityRateLimiter.Policy.REPORT_IP, ip);
        assertThatThrownBy(() -> admitIpOk(CommunityRateLimiter.Policy.REPORT_IP, ip))
                .isInstanceOf(CommunityException.class);

        // Keys: 64 lowercase hex, deterministic per identity, dimension-
        // separated, and free of plaintext user ids / IP literals.
        List<String> keys = jdbc.queryForList(
                "SELECT bucket_key FROM community_rate_buckets", String.class);
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            assertThat(HEX64.matcher(key).matches())
                    .as("bucket keys are HMAC digests, not plaintext: %s", key)
                    .isTrue();
        }
        String userKey = rateLimiter.userBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA);
        String userKeyAgain = rateLimiter.userBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA);
        String ipKey = rateLimiter.ipBucketKey(CommunityRateLimiter.Policy.REPORT_IP, ip);
        assertThat(userKey).isEqualTo(userKeyAgain);
        assertThat(keys).contains(userKey, ipKey);
        assertThat(userKey).isNotEqualTo(ipKey);
        assertThat(userKey).isNotEqualTo(rateLimiter.ipBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, String.valueOf(userA)));
        assertThat(rateLimiter.userBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userA)).isNotEqualTo(rateLimiter.userBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userB));
        for (String key : keys) {
            assertThat(key).doesNotContain(ip);
            assertThat(key).doesNotContain(String.valueOf(userA));
        }
    }

    // ==================================================================
    // fail closed
    // ==================================================================

    @Test
    void admissionFailsClosedWhenBucketStoreIsUnavailable() throws Exception {
        long teacherId = newTeacher();
        // Make the bucket store unavailable (rename), verify the request
        // FAILS (never admits), then restore — DDL renames in the finally
        // block keep the shared schema intact for the remaining tests.
        jdbc.execute("ALTER TABLE community_rate_buckets RENAME TO community_rate_buckets_offline");
        try {
            assertThatThrownBy(() -> admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, 42L))
                    .as("component-level admission fails closed on bucket-store failure")
                    .isInstanceOf(DataAccessException.class);

            long examId = newPublishedExam(teacherId);
            int status = mockMvc.perform(post("/api/community/publications")
                            .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"examId\":" + examId + ",\"title\":\"x\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status)
                    .as("HTTP-level fail closed: the request errors, it is never admitted")
                    .isGreaterThanOrEqualTo(500);
        } finally {
            jdbc.execute("ALTER TABLE community_rate_buckets_offline RENAME TO community_rate_buckets");
        }
        // Store restored → admission works again.
        admitOk(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, 43L);
    }

    // ==================================================================
    // concurrency of the upsert itself
    // ==================================================================

    @Test
    void concurrentAdmissionsOnOneBucketNeverViolateConstraints() throws Exception {
        long userId = 9_100_000_000L + (seq++ % 1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            start.await();
            try {
                txTemplate.executeWithoutResult(tx -> rateLimiter
                        .admitUser(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userId));
                return true;
            } catch (CommunityException ex) {
                return false; // saturation, not a constraint violation
            }
        };
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Boolean> future : futures) {
            admitted += future.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(admitted).as("exactly the 2 configured admissions pass").isEqualTo(2);
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(CommunityRateLimiter.Policy.PUBLISH_UNPUBLISH, userId));
        assertThat(attempts).as("every concurrent attempt was counted exactly once").isEqualTo(8);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private void admitOk(CommunityRateLimiter.Policy policy, long userId) {
        txTemplate.executeWithoutResult(tx -> rateLimiter.admitUser(policy, userId));
    }

    private void admitIpOk(CommunityRateLimiter.Policy policy, String ip) {
        txTemplate.executeWithoutResult(tx -> rateLimiter.admitIp(policy, ip));
    }

    private long newTeacher() {
        long id = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('rlt%s', 'rlt%s@t.example.com', 'h', 'RL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(seq, seq++));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = 'TEACHER'",
                id);
        return id;
    }

    private long newPublishedExam(long ownerId) {
        return insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RLX%s', 'RL exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
    }

    private long createPublishedPublication(long teacherId) throws Exception {
        long examId = newPublishedExam(teacherId);
        org.springframework.test.web.servlet.MvcResult created = mockMvc.perform(
                        post("/api/community/publications")
                                .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId))))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"examId\":" + examId + ",\"title\":\"t\",\"authorAlias\":\"a\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long publicationId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created.getResponse().getContentAsString())
                .get("publicationId").asLong();
        return publicationId;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
