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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for #66 comment reports (ADR-007 §5): submission
 * 201 with the §5 response shape, the ON CONFLICT dedupe 200 returning
 * the SAME report, re-report after handling opens a NEW report, the
 * byte-identical COMMUNITY_COMMENT_NOT_FOUND 404s for absent /
 * non-VISIBLE comments (HIDDEN, DELETED) and comments of §4-invisible
 * parents, admission consumed on every 404 path, and the bean-shape
 * rejections (blank reasonCode → 400 before admission).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityCommentReportIntegrationTests {

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
    private long commentId;

    @BeforeEach
    void setUp() throws Exception {
        teacherId = newUser("crt", "TEACHER");
        studentId = newUser("crs", "STUDENT");
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'CRX%s', 'Report fixture exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, System.nanoTime()));
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Report fixture publication', 'Cr-Alias', 'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
        commentId = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'Rep-Alias', 'reportable')
                """.formatted(publicationId, studentId));
    }

    // ==================================================================
    // submission + dedupe + reopen-after-handled
    // ==================================================================

    @Test
    void firstSubmissionIs201DedupeIs200WithTheSameReportHandledReopensNew()
            throws Exception {
        MvcResult first = mockMvc.perform(report(commentId, studentId, "SPAM", "noise"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.commentId").value((int) commentId))
                .andExpect(jsonPath("$.reporterUserId").value((int) studentId))
                .andExpect(jsonPath("$.reasonCode").value("SPAM"))
                .andExpect(jsonPath("$.details").value("noise"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        long reportId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(first.getResponse().getContentAsString()).get("reportId").asLong();

        // Dedupe: 200 with the SAME report (no reporter-side state oracle).
        mockMvc.perform(report(commentId, studentId, "OTHER", "different details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value((int) reportId))
                .andExpect(jsonPath("$.reasonCode").value("SPAM"))
                .andExpect(jsonPath("$.details").value("noise"));
        assertThat(openReportCount()).isEqualTo(1);

        // After handling, a NEW OPEN report can be submitted.
        jdbc.update("UPDATE community_comment_reports SET status = 'DISMISSED' WHERE id = ?",
                reportId);
        mockMvc.perform(report(commentId, studentId, "SPAM", null))
                .andExpect(status().isCreated());
        assertThat(openReportCount()).isEqualTo(1);
        assertThat(totalReportCount()).isEqualTo(2);
    }

    @Test
    void multipleReportersEachHoldOneOpenReport() throws Exception {
        long other = newUser("cro", "STUDENT");
        mockMvc.perform(report(commentId, studentId, "SPAM", null))
                .andExpect(status().isCreated());
        mockMvc.perform(report(commentId, other, "COPYRIGHT", null))
                .andExpect(status().isCreated());
        mockMvc.perform(report(commentId, other, "SPAM", null))
                .andExpect(status().isOk());
        assertThat(openReportCount()).isEqualTo(2);
    }

    // ==================================================================
    // 404s: absent / non-VISIBLE comment / invisible parent — identical
    // ==================================================================

    @Test
    void absentHiddenDeletedAndInvisibleParentAreTheSame404AndConsumeAdmission() throws Exception {
        long hidden = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body, state)
                VALUES (%d, %d, 'H-Alias', 'hidden body', 'HIDDEN')
                """.formatted(publicationId, studentId));
        long deleted = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body, state)
                VALUES (%d, %d, 'D-Alias', '', 'DELETED')
                """.formatted(publicationId, studentId));
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        long invisibleParentComment = insert("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body)
                VALUES (%d, %d, 'P-Alias', 'on a hidden publication')
                """.formatted(publicationId, studentId));
        String absent = reportEnvelope(9_999_999L);
        String hiddenEnvelope = reportEnvelope(hidden);
        String deletedEnvelope = reportEnvelope(deleted);
        String invisibleParent = reportEnvelope(invisibleParentComment);
        assertThat(hiddenEnvelope).isEqualTo(absent);
        assertThat(deletedEnvelope).isEqualTo(absent);
        assertThat(invisibleParent).isEqualTo(absent);
        assertThat(absent).contains("COMMUNITY_COMMENT_NOT_FOUND");

        // Nothing landed; admission ran for every syntactically valid probe.
        assertThat(totalReportCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(
                        CommunityRateLimiter.Policy.COMMENT_REPORT_USER, studentId)))
                .isEqualTo(1);
    }

    // ==================================================================
    // shape rejections (bean layer, before admission)
    // ==================================================================

    @Test
    void blankReasonCodeIs400BeforeAdmission() throws Exception {
        mockMvc.perform(report(commentId, studentId, " ", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/community/comments/" + commentId + "/reports")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"details\":\"no reason\"}"))
                .andExpect(status().isBadRequest());
        assertThat(totalReportCount()).isZero();
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private String reportEnvelope(long commentId) throws Exception {
        MvcResult result = mockMvc.perform(report(commentId, studentId, "SPAM", null))
                .andExpect(status().isNotFound()).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll("\"timestamp\":\"[^\"]+\"", "")
                .replaceAll("\"path\":\"[^\"]+\"", "");
    }

    private static org.springframework.test.web.servlet.RequestBuilder report(long commentId,
            long userId, String reason, String details) {
        return post("/api/community/comments/" + commentId + "/reports")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"" + reason + "\",\"details\":"
                        + (details == null ? "null" : "\"" + details + "\"") + "}");
    }

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private int openReportCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_comment_reports WHERE status = 'OPEN'",
                Integer.class);
    }

    private int totalReportCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_comment_reports", Integer.class);
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'CR', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, System.nanoTime(), prefix, System.nanoTime(),
                System.nanoTime()));
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
