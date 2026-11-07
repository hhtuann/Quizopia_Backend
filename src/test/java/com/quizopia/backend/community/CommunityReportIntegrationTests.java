package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityRateLimiter;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for POST /api/community/publications/{id}/reports
 * (ADR-006 §7, issue #42) on PostgreSQL 17 Testcontainers: the §7 fixed
 * order (admission → visibility 404 → dedupe 200 → 201), the bounded
 * reasonCode enum, the one-OPEN-per-reporter invariant (incl.
 * re-report after dismiss), the no-state-oracle rule (reporting a
 * since-hidden publication 404s), and the auth matrix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityReportIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityRateLimiter rateLimiter;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("rpt", "TEACHER");
        studentId = newUser("rps", "STUDENT");
        publicationId = createPublishedPublication();
    }

    // ==================================================================
    // §7 fixed order: 201 / dedupe 200
    // ==================================================================

    @Test
    void firstReportIs201AndRepeatIs200WithTheSameReport() throws Exception {
        MvcResult first = mockMvc.perform(
                        reportRequest(publicationId, studentId, "SPAM", "first details"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reasonCode").value("SPAM"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        long reportId = Long.parseLong(extract(first, "reportId"));

        mockMvc.perform(reportRequest(publicationId, studentId, "OTHER", "different details"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reportId").value(reportId))
                .andExpect(jsonPath("$.reasonCode").value("SPAM"))
                .andExpect(jsonPath("$.details").value("first details"))
                .andReturn();

        // Exactly one OPEN report for (reporter, publication).
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_reports
                        WHERE publication_id = ? AND reporter_user_id = ? AND status = 'OPEN'
                        """, Integer.class, publicationId, studentId))
                .isEqualTo(1);
    }

    @Test
    void differentReportersAndDifferentReasonsEachGetTheirOwnReport() throws Exception {
        mockMvc.perform(reportRequest(publicationId, studentId, "SPAM"))
                .andExpect(status().isCreated());
        mockMvc.perform(reportRequest(publicationId, teacherId, "COPYRIGHT", "my exam was copied"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM community_reports WHERE publication_id = ?
                        """, Integer.class, publicationId))
                .isEqualTo(2);
    }

    @Test
    void handledReportAllowsANewOpenReport() throws Exception {
        long reportId = submit(studentId, publicationId, "SPAM");
        jdbc.update("UPDATE community_reports SET status = 'DISMISSED' WHERE id = ?", reportId);

        // No OPEN row anymore → a fresh report inserts (201, new id).
        MvcResult reReported = mockMvc.perform(reportRequest(publicationId, studentId, "OTHER"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(Long.parseLong(extract(reReported, "reportId")))
                .as("re-reporting after a handled report creates a NEW report")
                .isNotEqualTo(reportId);
    }

    // ==================================================================
    // visibility 404 (step 2) — no reporter-side state oracle
    // ==================================================================

    @Test
    void reportingInvisibleOrAbsentPublicationsIs404() throws Exception {
        // HIDDEN source (the dedupe-after-hidden oracle stays closed).
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        mockMvc.perform(reportRequest(publicationId, studentId, "SPAM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        // UNPUBLISHED source.
        long unpublished = createPublishedPublication();
        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?", unpublished);
        mockMvc.perform(reportRequest(unpublished, studentId, "SPAM"))
                .andExpect(status().isNotFound());

        // Absent id — anti-enumeration identical body.
        mockMvc.perform(reportRequest(9_999_999L, studentId, "SPAM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
    }

    // ==================================================================
    // validation (before admission — no bucket consumed)
    // ==================================================================

    @Test
    void invalidBodiesAre400BeforeAdmission() throws Exception {
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/reports")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"NUISANCE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));

        mockMvc.perform(post("/api/community/publications/" + publicationId + "/reports")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\",\"details\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/community/publications/" + publicationId + "/reports")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"no reason\"}"))
                .andExpect(status().isBadRequest());

        // Nothing was written, and the invalid enum never consumed a
        // report admission for this reporter (semantic validation runs
        // BEFORE the §9 admission statement).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_reports WHERE publication_id = ?",
                Integer.class, publicationId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(
                        CommunityRateLimiter.Policy.REPORT_USER, studentId)))
                .isZero();
    }

    // ==================================================================
    // auth matrix (§7)
    // ==================================================================

    @Test
    void guestGets401AndRolelessCallerGets403() throws Exception {
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\"}"))
                .andExpect(status().isUnauthorized());

        long roleless = insertUser("rpr");
        mockMvc.perform(reportRequest(publicationId, roleless, "SPAM"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    @Test
    void studentAndTeacherAndForeignTeacherCanAllReport() throws Exception {
        mockMvc.perform(reportRequest(publicationId, studentId, "SPAM"))
                .andExpect(status().isCreated());
        // Foreign teacher (not the publisher) reports the same visible
        // publication — reports are reader-facing, not owner-scoped.
        long foreign = newUser("rpf", "TEACHER");
        mockMvc.perform(reportRequest(publicationId, foreign, "MISLEADING"))
                .andExpect(status().isCreated());
    }

    @Test
    void disabledReporterIsDeniedByTheServiceLayerDefenseInDepth() throws Exception {
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", studentId);
        mockMvc.perform(reportRequest(publicationId, studentId, "SPAM"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder reportRequest(long publicationId, long userId, String reasonCode) {
        return reportRequest(publicationId, userId, reasonCode, null);
    }

    private static RequestBuilder reportRequest(long publicationId, long userId,
            String reasonCode, String details) {
        String body = details == null
                ? "{\"reasonCode\":\"" + reasonCode + "\"}"
                : "{\"reasonCode\":\"" + reasonCode + "\",\"details\":\"" + details + "\"}";
        return post("/api/community/publications/" + publicationId + "/reports")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private long newUser(String prefix, String role) {
        long userId = insertUser(prefix);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'RPT', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A PUBLISHED + VISIBLE publication (the §4 predicate passes). */
    private long createPublishedPublication() {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'RPE%s', 'Report fixture exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        long publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Report fixture publication', 'Reporter-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
        return publicationId;
    }

    private long submit(long reporterId, long targetPublicationId, String reasonCode) throws Exception {
        MvcResult result = mockMvc.perform(reportRequest(targetPublicationId, reporterId, reasonCode))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(extract(result, "reportId"));
    }

    private static String extract(MvcResult result, String field) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get(field).asText();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
