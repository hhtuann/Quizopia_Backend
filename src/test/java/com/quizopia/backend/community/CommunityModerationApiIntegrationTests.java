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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the moderator API (ADR-006 §5/§7, issue #42) on
 * PostgreSQL 17 Testcontainers: the grant gate, the denial matrix
 * (guest/student/teacher-foreign/disabled/revoked) across every
 * moderation route, hide/restore state transitions with append-only
 * audit rows (incl. idempotent repeats and status-preserving restore),
 * report dismiss/resolve, the paged queue, per-publication history and
 * the owner-DTO openReportCount — plus the §4 public-catalog effect of
 * a moderator hide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityModerationApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long foreignTeacherId;
    private long moderatorId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("mod", "TEACHER");
        studentId = newUser("mos", "STUDENT");
        foreignTeacherId = newUser("mof", "TEACHER");
        moderatorId = newUser("modm", "STUDENT"); // dual-role moderator is fine (§5)
        grantModerator(moderatorId);
        publicationId = createPublishedPublication();
    }

    // ==================================================================
    // grant gate
    // ==================================================================

    @Test
    void grantEndpointReturnsGrantedTrueOnlyWithTheGrant() throws Exception {
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(teacherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    // ==================================================================
    // denial matrix on every moderation route (§7)
    // ==================================================================

    @Test
    void everyModerationRouteDeniesGuestsStudentsForeignTeachersAndDisabled() throws Exception {
        long reportId = submitReport(studentId, publicationId, "SPAM");
        String[] routes = {
                "/api/community/moderation/reports",
                "/api/community/moderation/publications/" + publicationId + "/hide",
                "/api/community/moderation/publications/" + publicationId + "/restore",
                "/api/community/moderation/reports/" + reportId + "/dismiss",
                "/api/community/moderation/reports/" + reportId + "/resolve",
                "/api/community/moderation/publications/" + publicationId + "/history"
        };

        for (String route : routes) {
            mockMvc.perform(get(route)) // guest (GET on POST routes = 405 first)
                    .andExpect(status().isUnauthorized());
        }
        // Guest on genuinely-GET routes.
        mockMvc.perform(get("/api/community/moderation/grant"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/community/moderation/reports"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/community/moderation/publications/" + publicationId + "/history"))
                .andExpect(status().isUnauthorized());

        for (long denied : new long[] {studentId, teacherId, foreignTeacherId}) {
            mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(denied)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/community/moderation/reports").with(jwtSubject(denied)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                            .with(jwtSubject(denied)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"r\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/restore")
                            .with(jwtSubject(denied)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"r\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/dismiss")
                            .with(jwtSubject(denied)).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/resolve")
                            .with(jwtSubject(denied)).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/community/moderation/publications/" + publicationId + "/history")
                            .with(jwtSubject(denied)))
                    .andExpect(status().isForbidden());
        }

        // DISABLED moderator: service-layer defense-in-depth 403 (the
        // AuthN-layer 401 for real tokens is covered by
        // CommunityModerationAuthNIntegrationTests).
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", moderatorId);
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());

        // Nothing was hidden and no audit rows were written by denials.
        assertThat(moderationState(publicationId)).isEqualTo("VISIBLE");
        assertThat(auditCount(publicationId)).isZero();
    }

    @Test
    void revokedGrantDeniesTheVeryNextRequest() throws Exception {
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk());

        revokeModerator(moderatorId); // mid-session user_roles deletion

        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"late hide\"}"))
                .andExpect(status().isForbidden());
        assertThat(moderationState(publicationId)).isEqualTo("VISIBLE");
    }

    @Test
    void expiredGrantDeniesImmediately() throws Exception {
        jdbc.update("""
                UPDATE user_roles
                SET assigned_at = now() - interval '2 seconds',
                    expires_at = now() - interval '1 second'
                WHERE user_id = ?
                  AND role_id = (SELECT id FROM roles WHERE code = 'COMMUNITY_MODERATOR')
                """, moderatorId);
        mockMvc.perform(get("/api/community/moderation/grant").with(jwtSubject(moderatorId)))
                .andExpect(status().isForbidden());
    }

    // ==================================================================
    // hide / restore: transitions, idempotence, audit append-only
    // ==================================================================

    @Test
    void hideTransitionsVisibleToHiddenWithAuditRow() throws Exception {
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam wave\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.moderationState").value("HIDDEN"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        assertThat(moderationState(publicationId)).isEqualTo("HIDDEN");
        assertThat(statusOf(publicationId)).isEqualTo("PUBLISHED");
        assertThat(auditRows(publicationId)).containsExactly(
                "HIDE|PUBLISHED>PUBLISHED|VISIBLE>HIDDEN|spam wave|" + moderatorId);
    }

    @Test
    void repeatHideIsAnIdempotentNoOpWithoutDuplicateAudit() throws Exception {
        hide(publicationId, moderatorId, "first hide");
        hide(publicationId, moderatorId, "second hide");

        assertThat(moderationState(publicationId)).isEqualTo("HIDDEN");
        assertThat(auditCount(publicationId)).isEqualTo(1);
        assertThat(auditRows(publicationId)).allSatisfy(row -> assertThat(row).contains("first hide"));
    }

    @Test
    void restoreTransitionsHiddenToVisibleWithoutTouchingStatus() throws Exception {
        hide(publicationId, moderatorId, "hide it");

        // Owner unpublishes while hidden.
        unpublishAsOwner(publicationId);

        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/restore")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"false alarm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationState").value("VISIBLE"))
                .andExpect(jsonPath("$.status").value("UNPUBLISHED"));

        // Restore does NOT flip status (§4): stays UNPUBLISHED.
        assertThat(statusOf(publicationId)).isEqualTo("UNPUBLISHED");
        assertThat(auditRows(publicationId)).containsExactly(
                "HIDE|PUBLISHED>PUBLISHED|VISIBLE>HIDDEN|hide it|" + moderatorId,
                "RESTORE|UNPUBLISHED>UNPUBLISHED|HIDDEN>VISIBLE|false alarm|" + moderatorId);
    }

    @Test
    void repeatRestoreIsAnIdempotentNoOpWithoutDuplicateAudit() throws Exception {
        hide(publicationId, moderatorId, "hide");
        restore(publicationId, moderatorId, "restore once");
        restore(publicationId, moderatorId, "restore twice");

        assertThat(moderationState(publicationId)).isEqualTo("VISIBLE");
        assertThat(auditCount(publicationId)).isEqualTo(2);
    }

    @Test
    void hideRequiresAMandatoryReason() throws Exception {
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        // Body-less hide is equally invalid.
        mockMvc.perform(post("/api/community/moderation/publications/" + publicationId + "/hide")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isBadRequest());

        assertThat(moderationState(publicationId)).isEqualTo("VISIBLE");
        assertThat(auditCount(publicationId)).isZero();
    }

    @Test
    void hideOnAbsentPublicationIs404() throws Exception {
        mockMvc.perform(post("/api/community/moderation/publications/990011/hide")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        mockMvc.perform(get("/api/community/moderation/publications/990011/history")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isNotFound());
    }

    // ==================================================================
    // hidden source leaves the public catalog + detail (§4)
    // ==================================================================

    @Test
    void hiddenSourceVanishesFromPublicCatalogAndDetail() throws Exception {
        mockMvc.perform(get("/api/public/community/publications/" + publicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId));

        hide(publicationId, moderatorId, "public harm");

        mockMvc.perform(get("/api/public/community/publications/" + publicationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        MvcResult catalog = mockMvc.perform(get("/api/public/community/publications?size=50"))
                .andExpect(status().isOk()).andReturn();
        // Item-level check: a substring scan of the raw envelope would
        // collide with "size":50 whenever the shared-container sequence
        // happens to assign publicationId == 50.
        List<Integer> visibleIds = com.jayway.jsonpath.JsonPath.read(
                catalog.getResponse().getContentAsString(), "$.items[*].publicationId");
        assertThat(visibleIds).doesNotContain(publicationId.intValue());
    }

    // ==================================================================
    // dismiss / resolve
    // ==================================================================

    @Test
    void dismissAndResolveHandleOpenReportsExactlyOnceWithAudit() throws Exception {
        long spamReport = submitReport(studentId, publicationId, "SPAM");
        long copyrightReport = submitReport(teacherId, publicationId, "COPYRIGHT");

        mockMvc.perform(post("/api/community/moderation/reports/" + spamReport + "/dismiss")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"not spam\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.handledByUserId").value(moderatorId))
                .andExpect(jsonPath("$.resolutionNote").value("not spam"))
                .andExpect(jsonPath("$.handledAt").isNotEmpty());

        assertThat(jdbc.queryForMap(
                "SELECT status, handled_by, resolution_note FROM community_reports WHERE id = ?",
                spamReport))
                .containsEntry("status", "DISMISSED")
                .containsEntry("handled_by", moderatorId);

        // A handled report is closed for good — second dismiss is 409.
        mockMvc.perform(post("/api/community/moderation/reports/" + spamReport + "/dismiss")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_REPORT_NOT_OPEN"));

        // Resolve without a note: audit reason falls back to the verb (≥1 char).
        mockMvc.perform(post("/api/community/moderation/reports/" + copyrightReport + "/resolve")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").doesNotExist());

        // Note: PG string concatenation is NULL-propagating, so the
        // report-action audit probe coalesces the nullable states.
        assertThat(jdbc.queryForList("""
                        SELECT a.action || '|' || coalesce(a.report_id::text, '-')
                             || '|' || a.reason || '|' || a.actor_user_id
                        FROM community_moderation_actions a
                        WHERE a.publication_id = ?
                        ORDER BY a.id
                        """, String.class, publicationId)).containsExactly(
                "REPORT_DISMISS|" + spamReport + "|not spam|" + moderatorId,
                "REPORT_RESOLVE|" + copyrightReport + "|REPORT_RESOLVE|" + moderatorId);
    }

    @Test
    void dismissOnAbsentReportIs404() throws Exception {
        mockMvc.perform(post("/api/community/moderation/reports/990012/dismiss")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    // ==================================================================
    // queue
    // ==================================================================

    @Test
    void queueIsPagedWithStatusFilterAndPublicationCard() throws Exception {
        long otherPublication = createPublishedPublication();
        long openReport = submitReport(studentId, publicationId, "SPAM");
        long dismissedReport = submitReport(teacherId, publicationId, "COPYRIGHT");
        jdbc.update("UPDATE community_reports SET status='DISMISSED' WHERE id = ?", dismissedReport);
        submitReport(studentId, otherPublication, "MISLEADING");

        mockMvc.perform(get("/api/community/moderation/reports").with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                // FE #44 pinned queue card fields on the nested publication.
                .andExpect(jsonPath("$.items[0].publicationId")
                        .value(otherPublication)) // newest first
                .andExpect(jsonPath("$.items[0].publication.title").isNotEmpty())
                .andExpect(jsonPath("$.items[0].publication.authorName").value("Mod-Alias"))
                .andExpect(jsonPath("$.items[0].publication.questionCount").value(0))
                .andExpect(jsonPath("$.items[0].publication.durationMinutes").value(0))
                .andExpect(jsonPath("$.items[0].publication.moderationState").value("VISIBLE"))
                .andExpect(jsonPath("$.items[0].reporterUserId").value(studentId));

        mockMvc.perform(get("/api/community/moderation/reports?status=OPEN")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/community/moderation/reports?status=DISMISSED")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(dismissedReport));

        mockMvc.perform(get("/api/community/moderation/reports?status=BOGUS")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/community/moderation/reports?page=0&size=2")
                        .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // ==================================================================
    // history
    // ==================================================================

    @Test
    void historyIsNewestFirstWithActorAsUserIdOnly() throws Exception {
        long reportId = submitReport(studentId, publicationId, "SPAM");
        hide(publicationId, moderatorId, "first");
        restore(publicationId, moderatorId, "second");
        mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/dismiss")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"third\"}"))
                .andExpect(status().isOk());

        String history = mockMvc.perform(
                        get("/api/community/moderation/publications/" + publicationId + "/history")
                                .with(jwtSubject(moderatorId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(history);
        List<String> actions = new java.util.ArrayList<>();
        root.forEach(entry -> {
            actions.add(entry.get("action").asText());
            assertThat(entry.has("actorUsername")).isFalse();
            assertThat(entry.get("actorUserId").asLong()).isEqualTo(moderatorId);
        });
        // Newest first.
        assertThat(actions).containsExactly("REPORT_DISMISS", "RESTORE", "HIDE");
    }

    // ==================================================================
    // owner DTO openReportCount
    // ==================================================================

    @Test
    void ownerDtoCarriesOpenReportCountThatFallsWhenHandled() throws Exception {
        submitReport(studentId, publicationId, "SPAM");
        submitReport(teacherId, publicationId, "COPYRIGHT");

        mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].openReportCount").value(2));

        long reportId = jdbc.queryForObject(
                "SELECT id FROM community_reports WHERE publication_id = ? ORDER BY id LIMIT 1",
                Long.class, publicationId);
        mockMvc.perform(post("/api/community/moderation/reports/" + reportId + "/dismiss")
                        .with(jwtSubject(moderatorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"handled\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].openReportCount").value(1));
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long newUser(String prefix, String role) {
        long userId = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'MOD', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, role);
        return userId;
    }

    private void grantModerator(long userId) {
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'COMMUNITY_MODERATOR'
                  AND NOT EXISTS (
                      SELECT 1 FROM user_roles ur
                      JOIN roles r ON r.id = ur.role_id
                      WHERE ur.user_id = ? AND r.code = 'COMMUNITY_MODERATOR')
                """, userId, userId);
    }

    private void revokeModerator(long userId) {
        jdbc.update("""
                DELETE FROM user_roles ur
                WHERE ur.user_id = ?
                  AND ur.role_id = (SELECT id FROM roles WHERE code = 'COMMUNITY_MODERATOR')
                """, userId);
    }

    /** A PUBLISHED + VISIBLE publication owned by the teacher. */
    private long createPublishedPublication() {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'MDE%s', 'Moderation fixture exam', 'PUBLISHED', now(), 1)
                """.formatted(teacherId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, published_at)
                VALUES (%d, %d, 'Moderation fixture publication', 'Mod-Alias',
                        'PUBLISHED', 'VISIBLE', now())
                """.formatted(examId, teacherId));
    }

    private long submitReport(long reporterId, long targetPublicationId, String reasonCode)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/community/publications/" + targetPublicationId + "/reports")
                                .with(jwtSubject(reporterId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reasonCode\":\"" + reasonCode + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get("reportId").asLong();
    }

    private void hide(long targetPublicationId, long actorId, String reason) throws Exception {
        mockMvc.perform(post("/api/community/moderation/publications/" + targetPublicationId + "/hide")
                        .with(jwtSubject(actorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private void restore(long targetPublicationId, long actorId, String reason) throws Exception {
        mockMvc.perform(post("/api/community/moderation/publications/" + targetPublicationId + "/restore")
                        .with(jwtSubject(actorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    private void unpublishAsOwner(long targetPublicationId) throws Exception {
        mockMvc.perform(post("/api/community/publications/" + targetPublicationId + "/unpublish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk());
    }

    private String moderationState(long targetPublicationId) {
        return jdbc.queryForObject(
                "SELECT moderation_state FROM community_publications WHERE id = ?",
                String.class, targetPublicationId);
    }

    private String statusOf(long targetPublicationId) {
        return jdbc.queryForObject(
                "SELECT status FROM community_publications WHERE id = ?",
                String.class, targetPublicationId);
    }

    private int auditCount(long targetPublicationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM community_moderation_actions WHERE publication_id = ?",
                Integer.class, targetPublicationId);
        return count == null ? 0 : count;
    }

    /** action|from>to states|reason|actor — HIDE/RESTORE shape. */
    private List<String> auditRows(long targetPublicationId) {
        return jdbc.queryForList("""
                        SELECT a.action || '|' || a.from_status || '>' || a.to_status
                             || '|' || a.from_moderation_state || '>' || a.to_moderation_state
                             || '|' || a.reason || '|' || a.actor_user_id
                        FROM community_moderation_actions a
                        WHERE a.publication_id = ?
                        ORDER BY a.id
                        """, String.class, targetPublicationId);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
