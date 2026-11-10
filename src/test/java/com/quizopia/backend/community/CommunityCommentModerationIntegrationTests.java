package com.quizopia.backend.community;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for #66 comment moderation (ADR-007 §5): every route
 * is grant-gated (non-moderator 403, revoked grant 403 on the next
 * call), hide removes the comment from the public page while the queue
 * keeps showing it, restore brings it back, repeat hide/restore are 200
 * no-ops WITHOUT a duplicate audit row, hide/restore of DELETED is 409
 * COMMUNITY_COMMENT_STATE_INVALID, dismiss/resolve transition the report
 * (409 COMMUNITY_REPORT_NOT_OPEN when handled) and audit with
 * comment_report_id, per-comment history is newest-first with
 * VISIBLE↔HIDDEN states and NULL statuses, and the queue DTO's nested
 * comment block carries exactly {body, authorName, createdAt} — the
 * author's CURRENT display name (Rev 4 / #72), no author user id, no
 * email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityCommentModerationIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long moderatorId;
    private long publicationId;
    private long commentId;

    @BeforeEach
    void setUp() throws Exception {
        teacherId = newUser("mmt", "TEACHER");
        studentId = newUser("mms", "STUDENT");
        moderatorId = newModerator();
        publicationId = createPublished();
        commentId = createCommentRaw(studentId, "moderation target");
    }

    // ==================================================================
    // grant gate: non-moderator 403 on every comment-moderation route
    // ==================================================================

    @Test
    void nonModeratorGets403OnEveryCommentModerationRoute() throws Exception {
        String[] routes = {
                "GET /api/community/moderation/comment-reports",
                "GET /api/community/moderation/comments/" + commentId + "/history",
                "POST /api/community/moderation/comments/" + commentId + "/hide",
                "POST /api/community/moderation/comments/" + commentId + "/restore",
                "POST /api/community/moderation/comment-reports/1/dismiss",
                "POST /api/community/moderation/comment-reports/1/resolve"};
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            RequestBuilder request = parts[0].equals("GET")
                    ? get(parts[1]).with(jwtSubject(studentId))
                    : post(parts[1]).with(jwtSubject(studentId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}");
            MvcResult denied = mockMvc.perform(request)
                    .andExpect(status().isForbidden()).andReturn();
            assertThat(denied.getResponse().getContentAsString())
                    .as("route %s must be service-layer 403, got %s",
                            route, denied.getResponse().getContentAsString())
                    .contains("COMMUNITY_ACCESS_DENIED");
        }
        // …and guests never reach the service layer at all (401).
        mockMvc.perform(get("/api/community/moderation/comment-reports"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // hide / restore: state machine, audit discipline, public opacity
    // ==================================================================

    @Test
    void hideExcludesFromPublicPageRestoreBringsBackRepeatsAreNoOpsWithoutDuplicateAudit()
            throws Exception {
        mockMvc.perform(hide(commentId, "spam"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("HIDDEN"))
                .andExpect(jsonPath("$.body").value("moderation target"));
        assertThat(publicCommentCount()).isZero();
        // Body retained server-side for moderation review.
        assertThat(bodyOf(commentId)).isEqualTo("moderation target");

        // Repeat hide: 200 no-op, NO duplicate audit row.
        mockMvc.perform(hide(commentId, "spam again"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("HIDDEN"));
        assertThat(auditCount("COMMENT_HIDE")).isEqualTo(1);

        mockMvc.perform(restore(commentId, "false positive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VISIBLE"));
        assertThat(publicCommentCount()).isEqualTo(1);
        mockMvc.perform(restore(commentId, "restore again"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VISIBLE"));
        assertThat(auditCount("COMMENT_RESTORE")).isEqualTo(1);

        // Audit shape: states in from/to_moderation_state, statuses NULL,
        // publication/report subjects NULL, comment_id linked.
        var row = jdbc.queryForMap("""
                SELECT from_moderation_state, to_moderation_state, from_status, to_status,
                       publication_id, report_id, comment_id, comment_report_id
                FROM community_moderation_actions WHERE action = 'COMMENT_HIDE'
                """);
        assertThat(row.get("from_moderation_state")).isEqualTo("VISIBLE");
        assertThat(row.get("to_moderation_state")).isEqualTo("HIDDEN");
        assertThat(row.get("from_status")).isNull();
        assertThat(row.get("to_status")).isNull();
        assertThat(row.get("publication_id")).isNull();
        assertThat(row.get("report_id")).isNull();
        assertThat(((Number) row.get("comment_id")).longValue()).isEqualTo(commentId);
        assertThat(row.get("comment_report_id")).isNull();
    }

    @Test
    void hideOrRestoreOfADeletedCommentIs409AndNeverAudited() throws Exception {
        mockMvc.perform(deleteComment(commentId, studentId)).andExpect(status().isOk());
        mockMvc.perform(hide(commentId, "too late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_STATE_INVALID"));
        mockMvc.perform(restore(commentId, "too late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_STATE_INVALID"));
        assertThat(auditCount("COMMENT_HIDE")).isZero();
        assertThat(auditCount("COMMENT_RESTORE")).isZero();
        assertThat(stateOf(commentId)).isEqualTo("DELETED");
    }

    @Test
    void hideReasonBoundsAre400() throws Exception {
        mockMvc.perform(post("/api/community/moderation/comments/" + commentId + "/hide")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(hide(commentId, " "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(hide(commentId, "r".repeat(501)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hideOrRestoreOfAbsentCommentIs404CommentNotFound() throws Exception {
        mockMvc.perform(hide(9_999_999L, "absent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_NOT_FOUND"));
        mockMvc.perform(restore(9_999_999L, "absent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_NOT_FOUND"));
    }

    // ==================================================================
    // queue: paged, status filter, nested shapes
    // ==================================================================

    @Test
    void commentReportQueueShowsReportCommentBlockAndPublicationCard() throws Exception {
        long reportId = reportCommentRaw(commentId, studentId, "SPAM", "queue probe");
        MvcResult queue = mockMvc.perform(get("/api/community/moderation/comment-reports")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        JsonNode entry = readTree(queue).get("items").get(0);
        assertThat(entry.get("id").asLong()).isEqualTo(reportId);
        assertThat(entry.get("commentId").asLong()).isEqualTo(commentId);
        // Nested comment block: exactly {body, authorName, createdAt} —
        // the comment author's CURRENT account display name ("MM").
        assertThat(entry.get("comment").get("body").asString()).isEqualTo("moderation target");
        assertThat(entry.get("comment").get("authorName").asString()).isEqualTo("MM");
        assertThat(entry.get("comment").hasNonNull("createdAt")).isTrue();
        assertThat(keySet(entry.get("comment")))
                .containsExactlyInAnyOrder("body", "authorName", "createdAt");
        // Publication card summary reused.
        assertThat(entry.get("publication").get("publicationId").asLong()).isEqualTo(publicationId);
        assertThat(entry.get("publication").get("authorName").asString()).isEqualTo("Mm-Alias");

        // Status filter.
        mockMvc.perform(get("/api/community/moderation/comment-reports")
                        .with(jwtSubject(moderatorId)).param("status", "RESOLVED"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/community/moderation/comment-reports")
                        .with(jwtSubject(moderatorId)).param("status", "OPEN"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/community/moderation/comment-reports")
                        .with(jwtSubject(moderatorId)).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    // ==================================================================
    // dismiss / resolve: transitions, audit linkage, not-open 409
    // ==================================================================

    @Test
    void dismissAndResolveTransitionWithAuditAndRejectHandledReports() throws Exception {
        long reportId = reportCommentRaw(commentId, studentId, "SPAM", null);
        long secondComment = createCommentRaw(studentId, "second");
        long reportId2 = reportCommentRaw(secondComment, studentId, "OTHER", null);

        mockMvc.perform(post("/api/community/moderation/comment-reports/" + reportId + "/dismiss")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"not spam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.handledBy").exists())
                .andExpect(jsonPath("$.resolutionNote").value("not spam"));

        // Handled → 409 COMMUNITY_REPORT_NOT_OPEN (reused code).
        mockMvc.perform(post("/api/community/moderation/comment-reports/" + reportId + "/dismiss")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_REPORT_NOT_OPEN"));

        mockMvc.perform(post("/api/community/moderation/comment-reports/" + reportId2 + "/resolve")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // Audit rows link comment_report_id, carry no states.
        assertThat(auditCount("COMMENT_REPORT_DISMISS")).isEqualTo(1);
        assertThat(auditCount("COMMENT_REPORT_RESOLVE")).isEqualTo(1);
        var dismissRow = jdbc.queryForMap("""
                SELECT comment_report_id, comment_id, from_moderation_state, to_moderation_state,
                       from_status, to_status
                FROM community_moderation_actions WHERE action = 'COMMENT_REPORT_DISMISS'
                """);
        assertThat(((Number) dismissRow.get("comment_report_id")).longValue()).isEqualTo(reportId);
        assertThat(dismissRow.get("comment_id")).isNull();
        assertThat(dismissRow.get("from_moderation_state")).isNull();
        assertThat(dismissRow.get("to_moderation_state")).isNull();
        assertThat(dismissRow.get("from_status")).isNull();
        assertThat(dismissRow.get("to_status")).isNull();

        // Reporting the same comment again after handling opens a NEW report.
        long reopened = reportAgain(commentId, studentId);
        assertThat(reopened).isNotEqualTo(reportId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_comment_reports WHERE comment_id = ?",
                Integer.class, commentId)).isEqualTo(2);
    }

    @Test
    void dismissOrResolveOfAbsentCommentReportIs404() throws Exception {
        mockMvc.perform(post("/api/community/moderation/comment-reports/9999999/dismiss")
                        .with(jwtSubject(moderatorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ==================================================================
    // per-comment history: newest-first, VISIBLE↔HIDDEN only
    // ==================================================================

    @Test
    void commentHistoryIsNewestFirstWithStatePairsOnly() throws Exception {
        mockMvc.perform(hide(commentId, "first action")).andExpect(status().isOk());
        long secondComment = createCommentRaw(studentId, "unrelated");
        mockMvc.perform(restore(commentId, "second action")).andExpect(status().isOk());
        mockMvc.perform(hide(secondComment, "other comment")).andExpect(status().isOk());

        MvcResult history = mockMvc.perform(get("/api/community/moderation/comments/"
                        + commentId + "/history").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode rows = readTree(history);
        assertThat(rows.size()).isEqualTo(2);
        // Newest first: RESTORE then HIDE.
        assertThat(rows.get(0).get("action").asString()).isEqualTo("COMMENT_RESTORE");
        assertThat(rows.get(1).get("action").asString()).isEqualTo("COMMENT_HIDE");
        assertThat(rows.get(0).get("fromModerationState").asString()).isEqualTo("HIDDEN");
        assertThat(rows.get(0).get("toModerationState").asString()).isEqualTo("VISIBLE");
        assertThat(rows.get(0).get("commentId").asLong()).isEqualTo(commentId);

        // Absent comment → 404.
        mockMvc.perform(get("/api/community/moderation/comments/9999999/history")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_NOT_FOUND"));
    }

    // ==================================================================
    // revoked grant: the permission resolves live per request
    // ==================================================================

    @Test
    void revokedModeratorGrantTakesEffectOnTheNextCall() throws Exception {
        mockMvc.perform(hide(commentId, "before revoke")).andExpect(status().isOk());
        jdbc.update("""
                DELETE FROM user_roles ur
                 USING roles r
                 WHERE ur.role_id = r.id AND ur.user_id = ? AND r.code = 'COMMUNITY_MODERATOR'
                """, moderatorId);
        mockMvc.perform(restore(commentId, "after revoke"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(get("/api/community/moderation/comment-reports")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden());
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private RequestBuilder hide(long commentId, String reason) {
        return post("/api/community/moderation/comments/" + commentId + "/hide")
                .with(jwtSubject(moderatorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":" + jsonString(reason) + "}");
    }

    private RequestBuilder restore(long commentId, String reason) {
        return post("/api/community/moderation/comments/" + commentId + "/restore")
                .with(jwtSubject(moderatorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":" + jsonString(reason) + "}");
    }

    private static RequestBuilder deleteComment(long commentId, long userId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/community/comments/" + commentId).with(jwtSubject(userId));
    }

    private long createCommentRaw(long userId, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/community/publications/"
                        + publicationId + "/comments")
                        .with(jwtSubject(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + jsonString(body) + "}"))
                .andExpect(status().isCreated()).andReturn();
        return readTree(result).get("commentId").asLong();
    }

    private long reportCommentRaw(long commentId, long userId, String reason, String details)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/community/comments/" + commentId + "/reports")
                        .with(jwtSubject(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"" + reason + "\",\"details\":"
                                + (details == null ? "null" : jsonString(details)) + "}"))
                .andExpect(status().isCreated()).andReturn();
        return readTree(result).get("reportId").asLong();
    }

    private long reportAgain(long commentId, long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/community/comments/" + commentId + "/reports")
                        .with(jwtSubject(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"SPAM\"}"))
                .andExpect(status().isCreated()).andReturn();
        return readTree(result).get("reportId").asLong();
    }

    private int publicCommentCount() throws Exception {
        MvcResult page = mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/comments")).andExpect(status().isOk()).andReturn();
        return readTree(page).get("totalElements").asInt();
    }

    private String bodyOf(long commentId) {
        return jdbc.queryForObject("SELECT body FROM community_comments WHERE id = ?",
                String.class, commentId);
    }

    private String stateOf(long commentId) {
        return jdbc.queryForObject("SELECT state FROM community_comments WHERE id = ?",
                String.class, commentId);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_moderation_actions WHERE action = ?",
                Integer.class, action);
    }

    private long newUser(String prefix, String role) {
        long userId = insertUser(prefix);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private long newModerator() {
        long userId = insertUser("mmm");
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'COMMUNITY_MODERATOR'
                  AND NOT EXISTS (
                      SELECT 1 FROM user_roles ur
                        JOIN roles r ON r.id = ur.role_id
                       WHERE ur.user_id = ? AND r.code = 'COMMUNITY_MODERATOR')
                """, userId, userId);
        return userId;
    }

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'MM', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 1 question (minimum eligibility shape). */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'MMB%s', 'bank')
                """.formatted(ownerId, seq++));
        long question = insert("""
                INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                VALUES (%d, 'MMU%s', 'ACTIVE', 1, %d)
                """.formatted(bank, seq++, ownerId));
        long version = insert("""
                INSERT INTO question_versions (question_id, version_number, question_type, content,
                                               difficulty, default_points, metadata, created_by)
                VALUES (%d, 1, 'SINGLE_CHOICE', 'content', 'MEDIUM', 1, '{}'::jsonb, %d)
                """.formatted(question, ownerId));
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points,
                                   duration_minutes, description)
                VALUES (%d, 'MMX%s', 'Moderation fixture exam', 'PUBLISHED', now(), 1, 10, 'd')
                """.formatted(ownerId, seq++));
        long section = insert("INSERT INTO exam_sections (exam_id, title, position)"
                + " VALUES (%d, 'S1', 0)".formatted(examId));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'MMC1', 'SINGLE_CHOICE', 'content', 1, 0, '{}'::jsonb)
                """.formatted(examId, section, question, version));
        return examId;
    }

    private long createPublished() throws Exception {
        long examId = newPublishedExam(teacherId);
        MvcResult created = mockMvc.perform(post("/api/community/publications")
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"Moderation fixture\","
                                + "\"authorAlias\":\"Mm-Alias\"}"))
                .andExpect(status().isCreated()).andReturn();
        long pubId = readTree(created).get("publicationId").asLong();
        mockMvc.perform(post("/api/community/publications/" + pubId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk());
        return pubId;
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static JsonNode readTree(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(result.getResponse().getContentAsString());
    }

    private static java.util.Set<String> keySet(JsonNode object) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        object.properties().forEach(property -> keys.add(property.getKey()));
        return keys;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
