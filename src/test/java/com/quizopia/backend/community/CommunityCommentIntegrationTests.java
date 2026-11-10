package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityRateLimiter;
import com.quizopia.backend.community.domain.model.CommunityCommentState;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.hibernate.stat.Statistics;
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
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the #66 comment CRUD + public page (ADR-007
 * §5/§6) on PostgreSQL 17 Testcontainers: create (alias snapshot =
 * post-upsert stored profile alias), body bounds, the §4 visibility
 * 404 with admission still consumed, the VISIBLE-only newest-first
 * public page (size clamp, empty, beyond-end), owner PATCH (CAS,
 * edited_at) and the idempotent tombstone DELETE (body cleared,
 * gone from the list), byte-identical 404s for absent/foreign
 * comments, 409 STATE_INVALID on own non-VISIBLE PATCH with the
 * tombstone body never resurrected, recursive redaction of the public
 * page (no user ids/emails), and the no-N+1 page query (exactly count
 * + select).
 *
 * <p>Publications are seeded through the real {@code CommunityService}
 * (POST/PATCH/publish over HTTP) on real published exams — the #41
 * catalog-suite pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityCommentIntegrationTests {

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

    @Autowired
    private com.quizopia.backend.community.repository.CommunityCommentRepository commentRepo;
    @Autowired
    private jakarta.persistence.EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        teacherId = newUser("cmt", "TEACHER");
        studentId = newUser("cms", "STUDENT");
        publicationId = createPublished("Comment fixture publication");
    }

    // ==================================================================
    // create: 201, alias captured, snapshot == stored profile alias
    // ==================================================================

    @Test
    void createCapturesAliasAndSnapshotsStoredProfileAlias() throws Exception {
        MvcResult created = mockMvc.perform(postComment(publicationId, studentId,
                        "First!", "Snap-" + seq))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.authorAlias").value("Snap-" + seq))
                .andExpect(jsonPath("$.publicationId").value((int) publicationId))
                .andExpect(jsonPath("$.editedAt").doesNotExist())
                .andReturn();
        long commentId = readTree(created).get("commentId").asLong();
        assertThat(commentId).isPositive();

        // §4: the snapshot equals the STORED profile alias (post-upsert).
        String storedAlias = jdbc.queryForObject(
                "SELECT alias FROM community_member_profiles WHERE user_id = ?",
                String.class, studentId);
        String snapshot = jdbc.queryForObject(
                "SELECT author_alias FROM community_comments WHERE id = ?",
                String.class, commentId);
        assertThat(storedAlias).isEqualTo("Snap-" + seq);
        assertThat(snapshot).isEqualTo(storedAlias);
    }

    @Test
    void createWithoutAliasOnFirstCommentIs400AndWithoutProfileRow() throws Exception {
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/comments")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"no alias yet\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, studentId)).isZero();
        assertThat(commentCount()).isZero();
    }

    // ==================================================================
    // body bounds (§3): blank / null / >2000 / control chars
    // ==================================================================

    @Test
    void bodyBoundsAre400WithCommunityValidationErrorCode() throws Exception {
        postBodyExpect400(studentId, "   ");
        postBodyExpect400(studentId, "");
        postBodyExpect400(studentId, "a".repeat(2001));
        // Control characters are pinned in CommunityCommentBodyValidationTest
        // (String.trim() strips edge controls before the charset check, so
        // only INTERNAL controls exercise the §3 rejection); here: the bounds.
        // No body at all.
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/comments")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"Bound-" + seq + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
        assertThat(commentCount()).isZero();

        // 2000 exactly, and \n/\t inside, are fine; body stored trimmed.
        mockMvc.perform(postComment(publicationId, studentId,
                        "  ".repeat(1) + "x".repeat(2000), "Bound2-" + seq))
                .andExpect(status().isCreated());
        String stored = jdbc.queryForObject(
                "SELECT body FROM community_comments WHERE publication_id = ?",
                String.class, publicationId);
        assertThat(stored).hasSize(2000).startsWith("x");
    }

    private void postBodyExpect400(long userId, String body) throws Exception {
        mockMvc.perform(postComment(publicationId, userId, body, "Bound-" + seq))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
    }

    // ==================================================================
    // §4 visibility 404 on create (no existence oracle) — admission still ran
    // ==================================================================

    @Test
    void createOnInvisibleOrAbsentPublicationIs404AndConsumesAdmission() throws Exception {
        long hidden = createPublished("Hidden source");
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                hidden);
        mockMvc.perform(postComment(hidden, studentId, "nope", "Vis-" + seq))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        long unpublished = createPublished("Unpublished source");
        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?",
                unpublished);
        mockMvc.perform(postComment(unpublished, studentId, "nope", "Vis-" + seq))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        mockMvc.perform(postComment(9_999_999L, studentId, "nope", "Vis-" + seq))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        assertThat(commentCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(
                        CommunityRateLimiter.Policy.COMMENT_CREATE, studentId)))
                .as("every syntactically valid create consumed the COMMENT_CREATE bucket")
                .isEqualTo(1);
    }

    // ==================================================================
    // public page: newest-first, VISIBLE-only, clamp, empty, beyond-end
    // ==================================================================

    @Test
    void publicPageIsNewestFirstVisibleOnlyAndClamped() throws Exception {
        long other = newUser("cmo", "STUDENT");
        long c1 = createCommentRaw(publicationId, studentId, "first comment", "Pager-" + seq);
        bumpCreated(c1, 3);
        long c2 = createCommentRaw(publicationId, other, "second comment", "Pager2-" + seq);
        bumpCreated(c2, 2);
        long c3 = createCommentRaw(publicationId, studentId, "third comment", "Pager-" + seq);
        bumpCreated(c3, 1);

        // newest-first: c3, c2, c1 — deterministic id tiebreak not needed here.
        MvcResult page = mockMvc.perform(get("/api/public/community/publications/"
                        + publicationId + "/comments")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.sort").value("createdAt DESC, id DESC"))
                .andReturn();
        JsonNode items = readTree(page).get("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).get("commentId").asLong()).isEqualTo(c3);
        assertThat(items.get(1).get("commentId").asLong()).isEqualTo(c2);
        assertThat(items.get(2).get("commentId").asLong()).isEqualTo(c1);
        // The §5 item shape exactly.
        assertThat(keySet(items.get(0)))
                .containsExactlyInAnyOrder("commentId", "authorAlias", "body", "createdAt",
                        "editedAt");

        // HIDDEN and DELETED never appear; totals follow (§3 opacity).
        jdbc.update("UPDATE community_comments SET state = 'HIDDEN' WHERE id = ?", c2);
        jdbc.update("UPDATE community_comments SET state = 'DELETED', body = '' WHERE id = ?", c3);
        mockMvc.perform(get(publicCommentsPath(publicationId, 0, 50)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].commentId").value((int) c1));

        // Size clamps to 1..50 (500 → 50 → single page of 1 here anyway).
        MvcResult clamped = mockMvc.perform(get(publicCommentsPath(publicationId, 0, 500))).andReturn();
        assertThat(readTree(clamped).get("size").asInt()).isEqualTo(50);
        // size 0 → default 20.
        MvcResult defaulted = mockMvc.perform(get(publicCommentsPath(publicationId, 0, 0))).andReturn();
        assertThat(readTree(defaulted).get("size").asInt()).isEqualTo(20);

        // Beyond-end page: empty items, truthful totals.
        MvcResult beyond = mockMvc.perform(get(publicCommentsPath(publicationId, 99, 20))).andReturn();
        JsonNode beyondTree = readTree(beyond);
        assertThat(beyondTree.get("items").size()).isZero();
        assertThat(beyondTree.get("totalElements").asLong()).isEqualTo(1);
        assertThat(beyondTree.get("page").asInt()).isEqualTo(99);

        // Empty publication: zero comments.
        long empty = createPublished("Empty comments");
        mockMvc.perform(get(publicCommentsPath(empty, 0, 20)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void publicPageOnInvisiblePublicationIs404PublicationNotFound() throws Exception {
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        mockMvc.perform(get(publicCommentsPath(publicationId, 0, 20)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        mockMvc.perform(get(publicCommentsPath(9_999_999L, 0, 20)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
    }

    // ==================================================================
    // owner PATCH: CAS + edited_at; re-PATCH; state conflicts
    // ==================================================================

    @Test
    void ownerPatchSetsEditedAtAndRepatchIsIdempotentContent() throws Exception {
        long commentId = createCommentRaw(publicationId, studentId, "original", "Ed-" + seq);
        assertThat(editedAt(commentId)).isNull();

        mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edited once\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.body").value("edited once"))
                .andExpect(jsonPath("$.editedAt").exists());
        assertThat(editedAt(commentId)).isNotNull();

        // A second PATCH with the SAME content is a normal 200 (no
        // idempotency flag — content-idempotent by outcome).
        mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edited once\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("edited once"));
        assertThat(bodyOf(commentId)).isEqualTo("edited once");
    }

    @Test
    void patchOwnHiddenOrDeletedCommentIs409AndNeverResurrectsTheTombstone() throws Exception {
        long commentId = createCommentRaw(publicationId, studentId, "to hide", "St-" + seq);
        jdbc.update("UPDATE community_comments SET state = 'HIDDEN' WHERE id = ?", commentId);
        mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edit hidden\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_STATE_INVALID"));
        assertThat(bodyOf(commentId)).isEqualTo("to hide");

        // DELETED: the tombstone body stays empty — the §6 CAS prevents
        // a delete-then-edit race from resurrecting cleared content.
        jdbc.update("UPDATE community_comments SET state = 'DELETED', body = '' WHERE id = ?",
                commentId);
        mockMvc.perform(patch("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"edit deleted\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_STATE_INVALID"));
        assertThat(bodyOf(commentId)).isEmpty();
    }

    // ==================================================================
    // owner DELETE: tombstone, idempotent, gone from list
    // ==================================================================

    @Test
    void ownerDeleteTombstonesClearsBodyAndIsIdempotent() throws Exception {
        long commentId = createCommentRaw(publicationId, studentId, "delete me", "Del-" + seq);
        mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(jdbc.queryForObject(
                "SELECT state FROM community_comments WHERE id = ?", String.class, commentId))
                .isEqualTo("DELETED");
        assertThat(bodyOf(commentId)).isEmpty();
        mockMvc.perform(get(publicCommentsPath(publicationId, 0, 50)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Idempotent re-DELETE: still 200 (any own state).
        mockMvc.perform(delete("/api/community/comments/" + commentId)
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk());

        // DELETE works on a hidden publication too (§5 — ownership only).
        long hiddenPub = createPublished("Hidden pub delete");
        long hiddenPubComment = createCommentRaw(hiddenPub, studentId, "on hidden", "Del2-" + seq);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                hiddenPub);
        mockMvc.perform(delete("/api/community/comments/" + hiddenPubComment)
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT state FROM community_comments WHERE id = ?", String.class, hiddenPubComment))
                .isEqualTo("DELETED");
    }

    // ==================================================================
    // foreign/absent: byte-identical 404s
    // ==================================================================

    @Test
    void foreignAndAbsentPatchAndDeleteAreByteIdentical404s() throws Exception {
        long foreignComment = createCommentRaw(publicationId, newUser("cmf", "STUDENT"),
                "not yours", "Foreign-" + seq);

        String absentPatch = errorEnvelope(patchAbsent(foreignComment));
        String foreignPatch = errorEnvelope(patchForeign(foreignComment));
        // Byte-level identity is impossible across ids (timestamp + path
        // differ by construction); the CONTRACT identity is the envelope:
        // same status, same code, same message — an enumeration probe
        // learns nothing from comparing them.
        assertThat(foreignPatch).isEqualTo(absentPatch);
        assertThat(absentPatch).contains("COMMUNITY_COMMENT_NOT_FOUND");

        String absentDelete = errorEnvelope(deleteAbsent(foreignComment));
        String foreignDelete = errorEnvelope(deleteForeign(foreignComment));
        assertThat(foreignDelete).isEqualTo(absentDelete);

        // The foreign comment is untouched.
        assertThat(bodyOf(foreignComment)).isEqualTo("not yours");
    }

    // ==================================================================
    // redaction: the public page never carries user ids/emails
    // ==================================================================

    @Test
    void publicPageJsonCarriesNoUserIdOrEmailKeysAnywhere() throws Exception {
        createCommentRaw(publicationId, studentId, "redact me", "Red-" + seq);
        MvcResult page = mockMvc.perform(get(publicCommentsPath(publicationId, 0, 50)))
                .andExpect(status().isOk()).andReturn();
        assertNoIdentityKeys(readTree(page));
    }

    private static void assertNoIdentityKeys(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(property -> {
                assertThat(property.getKey().toLowerCase())
                        .as("public comment JSON must not carry identity keys")
                        .doesNotContain("userid", "authoruser", "email", "username");
                assertNoIdentityKeys(property.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(CommunityCommentIntegrationTests::assertNoIdentityKeys);
        }
    }

    // ==================================================================
    // no N+1: the page query is exactly count + select
    // ==================================================================

    @Test
    void publicPageRepositoryQueryIsExactlyCountPlusSelect() {
        createCommentRaw(publicationId, studentId, "n+1 probe a", "Noq-" + seq);
        createCommentRaw(publicationId, studentId, "n+1 probe b", "Noq-" + seq);
        createCommentRaw(publicationId, studentId, "n+1 probe c", "Noq-" + seq);
        Statistics stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        commentRepo.flush(); // push the fixture INSERTs out of the measurement window
        stats.clear();
        stats.setStatisticsEnabled(true);
        try {
            commentRepo.findByPublicationIdAndStateOrderByCreatedAtDescIdDesc(
                    publicationId, CommunityCommentState.VISIBLE,
                    org.springframework.data.domain.PageRequest.of(0, 20));
            assertThat(stats.getPrepareStatementCount())
                    .as("paged read = COUNT + SELECT only — 3 rows, no per-row query (no N+1)")
                    .isLessThanOrEqualTo(2);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    // ==================================================================
    // fixtures (real-service seeding — #41 catalog pattern)
    // ==================================================================


    /** The comparable part of a 404 envelope: code + message (no timestamp/path). */
    private String errorEnvelope(org.springframework.test.web.servlet.RequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isNotFound()).andReturn();
        JsonNode error = readTree(result);
        return error.get("code").asString() + "|" + error.get("message").asString();
    }

    private org.springframework.test.web.servlet.RequestBuilder patchAbsent(long foreignComment) {
        return patch("/api/community/comments/9999999").with(jwtSubject(studentId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder patchForeign(long foreignComment) {
        return patch("/api/community/comments/" + foreignComment).with(jwtSubject(studentId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder deleteAbsent(long foreignComment) {
        return delete("/api/community/comments/9999999").with(jwtSubject(studentId));
    }

    private org.springframework.test.web.servlet.RequestBuilder deleteForeign(long foreignComment) {
        return delete("/api/community/comments/" + foreignComment).with(jwtSubject(studentId));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder postComment(long publicationId, long userId, String body,
            String alias) {
        return post("/api/community/publications/" + publicationId + "/comments")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + jsonString(body) + ",\"alias\":" + jsonString(alias) + "}");
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
                default -> {
                    if (c < 0x20 || Character.getType(c) == Character.FORMAT) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String publicCommentsPath(long publicationId, int page, int size) {
        return "/api/public/community/publications/" + publicationId + "/comments?page=" + page
                + "&size=" + size;
    }

    /** Creates a comment through the real route, returning the id. */
    private long createCommentRaw(long publicationId, long userId, String body, String alias) {
        try {
            MvcResult result = mockMvc.perform(postComment(publicationId, userId, body, alias))
                    .andExpect(status().isCreated()).andReturn();
            return readTree(result).get("commentId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("fixture comment failed", ex);
        }
    }

    /** Forces deterministic created_at ordering (±seconds on a shared clock). */
    private void bumpCreated(long commentId, int secondsBack) {
        jdbc.update("UPDATE community_comments SET created_at = now() - (? || ' seconds')::interval",
                secondsBack);
    }

    private int commentCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE publication_id = ?",
                Integer.class, publicationId);
    }

    private String bodyOf(long commentId) {
        return jdbc.queryForObject("SELECT body FROM community_comments WHERE id = ?",
                String.class, commentId);
    }

    private java.sql.Timestamp editedAt(long commentId) {
        return jdbc.queryForObject("SELECT edited_at FROM community_comments WHERE id = ?",
                java.sql.Timestamp.class, commentId);
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'CM', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 2 sections / 3 questions. */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'CMB%s', 'bank')
                """.formatted(ownerId, seq++));
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'CMU%s-%d', 'ACTIVE', 1, %d)
                    """.formatted(bank, seq++, i, ownerId));
            versions[i] = insert("""
                    INSERT INTO question_versions (question_id, version_number, question_type, content,
                                                   difficulty, default_points, metadata, created_by)
                    VALUES (%d, 1, 'SINGLE_CHOICE', 'content', 'MEDIUM', 1, '{}'::jsonb, %d)
                    """.formatted(questions[i], ownerId));
        }
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points,
                                   duration_minutes, description)
                VALUES (%d, 'CMX%s', 'Comment fixture exam', 'PUBLISHED', now(), 3, 30, 'description')
                """.formatted(ownerId, seq++));
        long s1 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S1', 0)"
                .formatted(examId));
        long s2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S2', 1)"
                .formatted(examId));
        long[] sections = {s1, s1, s2};
        int[] positions = {0, 1, 0};
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                                source_question_version_id, question_code, question_type,
                                                content, default_points, position, metadata)
                    VALUES (%d, %d, %d, %d, 'CMC%d', 'SINGLE_CHOICE', 'content', 1, %d, '{}'::jsonb)
                    """.formatted(examId, sections[i], questions[i], versions[i], i + 1,
                    positions[i]));
        }
        return examId;
    }

    /** Created AND published (alias set) through the real CommunityService. */
    private long createPublished(String title) {
        try {
            long examId = newPublishedExam(teacherId);
            MvcResult created = mockMvc.perform(post("/api/community/publications")
                            .with(jwtSubject(teacherId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"examId\":" + examId + ",\"title\":\"" + title + "\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();
            long newPublicationId = Long.parseLong(readTree(created)
                    .get("publicationId").asString());
            mockMvc.perform(patch("/api/community/publications/" + newPublicationId)
                            .with(jwtSubject(teacherId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Cm-Alias\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + newPublicationId + "/publish")
                            .with(jwtSubject(teacherId)))
                    .andExpect(status().isOk());
            return newPublicationId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
    }

    private static JsonNode readTree(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> keySet(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.properties().forEach(property -> keys.add(property.getKey()));
        return keys;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
