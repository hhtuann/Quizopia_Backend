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
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The §4 alias consent contract (ADR-007, #66) over HTTP: first-comment
 * capture (profile created, snapshot = stored), defaulting and
 * forward-only changes (snapshots stable), impersonation rejections
 * (own email/username, ANY username, ANY publication author_alias —
 * all ci — plus '@', zero-width/bidi/control charset and the 1..80
 * bounds), the publication reverse-check (publish/PATCH authorAlias
 * rejected when ci-equal to a member alias — the single
 * ADR-sanctioned P3-A touch), and the plain (non-race) ci collision →
 * 409 COMMUNITY_ALIAS_TAKEN with an atomic failure (no comment row, no
 * profile row for the loser). The RACE variants live in
 * {@code CommunityCommentConcurrencyIntegrationTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
// NOT @Transactional: the 409 alias-collision path must map a real
// DataIntegrityViolationException out of its own committed transaction
// (a joined test transaction would be aborted by PostgreSQL, 25P02).
// @DirtiesContext keeps the committed fixtures off the shared database.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityCommentAliasIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long otherStudentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("alt", "TEACHER");
        studentId = newUser("als", "STUDENT");
        otherStudentId = newUser("alo", "STUDENT");
        publicationId = createPublished();
    }

    // ==================================================================
    // first-comment capture + defaults + forward-only changes
    // ==================================================================

    @Test
    void firstCommentCapturesAliasAndLaterCommentsDefaultToStored() throws Exception {
        mockMvc.perform(postComment(studentId, "hello", "First-Alias-" + seq))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorAlias").value("First-Alias-" + seq));
        assertThat(storedAlias(studentId)).isEqualTo("First-Alias-" + seq);

        // Subsequent comment without alias defaults to the stored one.
        MvcResult second = mockMvc.perform(postComment(studentId, "second", null))
                .andExpect(status().isCreated()).andReturn();
        assertThat(readTree(second).get("authorAlias").asString()).isEqualTo("First-Alias-" + seq);
    }

    @Test
    void aliasChangeAppliesForwardOnlySnapshotsAreStable() throws Exception {
        long first = createCommentRaw(studentId, "before change", "Old-Alias-" + seq);
        long second = createCommentRaw(studentId, "after change", "New-Alias-" + seq);

        assertThat(storedAlias(studentId)).isEqualTo("New-Alias-" + seq);
        assertThat(snapshot(first)).isEqualTo("Old-Alias-" + seq);
        assertThat(snapshot(second)).isEqualTo("New-Alias-" + seq);

        // Re-submitting the stored alias is not a change (no error, no write).
        mockMvc.perform(postComment(studentId, "same again", "New-Alias-" + seq))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorAlias").value("New-Alias-" + seq));
    }

    // ==================================================================
    // impersonation + charset rejections (§4)
    // ==================================================================

    @Test
    void impersonationAndCharsetRejectionsAre400AliasInvalid() throws Exception {
        // ANY users.username, ci — including a foreign teacher's and the
        // caller's own (trimmed + case-flipped on purpose).
        String teacherUsername = jdbc.queryForObject(
                "SELECT username FROM users WHERE id = ?", String.class, teacherId);
        expectAliasInvalid(teacherUsername.toUpperCase());
        String ownUsername = jdbc.queryForObject(
                "SELECT username FROM users WHERE id = ?", String.class, studentId);
        expectAliasInvalid(" " + ownUsername.toLowerCase() + " ");

        // Own email, ci.
        String ownEmail = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, studentId);
        expectAliasInvalid(ownEmail);

        // ANY publication author_alias, ci (the fixture's "Al-Alias").
        expectAliasInvalid("al-alias");

        // '@' anywhere.
        expectAliasInvalid("someone@example.com");
        expectAliasInvalid("a@b");

        // Zero-width + bidi + control characters (never allowed in an alias).
        expectAliasInvalid("Ghost\u200BName");
        expectAliasInvalid("Rtl\u202EName");
        expectAliasInvalid("Iso\u2060l");
        expectAliasInvalid("Tab\tName");
        expectAliasInvalid("New\nline");

        // Nothing landed: no comment, no profile for the caller.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, studentId)).isZero();
        assertThat(commentCountFor(studentId)).isZero();
    }

    @Test
    void aliasBoundsAre400ValidationError() throws Exception {
        expectValidation("x".repeat(81));
        expectValidation("   ");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, studentId)).isZero();

        // 80 chars exactly is fine.
        mockMvc.perform(postComment(studentId, "at the bound", "y".repeat(80)))
                .andExpect(status().isCreated());
    }

    // ==================================================================
    // publication reverse-check (§4, the single P3-A touch)
    // ==================================================================

    @Test
    void publicationAuthorAliasRejectedWhenEqualToAnyMemberAliasCi() throws Exception {
        // A member (the student) now owns this alias (captured ONCE — the
        // static seq advances inside the exam fixtures below).
        String memberAlias = "Taken-Alias-" + seq;
        createCommentRaw(studentId, "claiming my alias", memberAlias);

        // PATCH of the fixture publication's authorAlias → 400 (the
        // current optimistic version read from the row, not guessed).
        int version = jdbc.queryForObject(
                "SELECT version FROM community_publications WHERE id = ?",
                Integer.class, publicationId);
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"authorAlias\":\""
                                + memberAlias.toLowerCase() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_INVALID"));
        // The publication alias is unchanged.
        assertThat(jdbc.queryForObject(
                "SELECT author_alias FROM community_publications WHERE id = ?",
                String.class, publicationId)).isEqualTo("Al-Alias");

        // CREATE with a member alias → 400 too.
        long examId = newPublishedExam(teacherId);
        mockMvc.perform(post("/api/community/publications")
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"Reverse-check probe\","
                                + "\"authorAlias\":\"" + memberAlias.toUpperCase() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_INVALID"));

        // PUBLISH re-validation: a member claiming the stored alias between
        // PATCH and publish blocks the publish (re-checked under the lock).
        // The publication already holds freeAlias; the API's own §4 probe
        // keeps members from claiming it afterwards, so a member profile
        // row is swooped in directly (the operator/DB race the publish-time
        // re-check exists for).
        String freeAlias = "Free-Alias-" + seq;
        long examId2 = newPublishedExam(teacherId);
        long pubId2 = createUnpublished(examId2, freeAlias);
        jdbc.update("INSERT INTO community_member_profiles (user_id, alias) VALUES (?, ?)",
                otherStudentId, freeAlias);
        mockMvc.perform(post("/api/community/publications/" + pubId2 + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_INVALID"));
    }

    // ==================================================================
    // plain ci collision → 409, atomic failure
    // ==================================================================

    @Test
    void secondUserTakingAnExistingAliasCiGets409AndNothingLands() throws Exception {
        createCommentRaw(studentId, "winner comment", "Clash-Alias-" + seq);

        mockMvc.perform(postComment(otherStudentId, "loser comment", "clash-alias-" + seq))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_TAKEN"));

        assertThat(commentCountFor(otherStudentId))
                .as("the loser's comment POST failed atomically")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_member_profiles WHERE user_id = ?",
                Integer.class, otherStudentId))
                .as("no profile row for the loser")
                .isZero();
        assertThat(storedAlias(studentId)).isEqualTo("Clash-Alias-" + seq);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private void expectAliasInvalid(String alias) throws Exception {
        mockMvc.perform(postComment(studentId, "probe body", alias))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_INVALID"));
    }

    private void expectValidation(String alias) throws Exception {
        mockMvc.perform(postComment(studentId, "probe body", alias))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
    }

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private RequestBuilder postComment(long userId, String body, String alias) {
        String aliasJson = alias == null ? "null" : jsonString(alias);
        return post("/api/community/publications/" + publicationId + "/comments")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":" + jsonString(body) + ",\"alias\":" + aliasJson + "}");
    }

    /** JSON string literal with control/format characters escaped. */
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

    private long createCommentRaw(long userId, String body, String alias) {
        try {
            MvcResult result = mockMvc.perform(postComment(userId, body, alias))
                    .andExpect(status().isCreated()).andReturn();
            return readTree(result).get("commentId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("fixture comment failed", ex);
        }
    }

    private String storedAlias(long userId) {
        return jdbc.queryForObject(
                "SELECT alias FROM community_member_profiles WHERE user_id = ?",
                String.class, userId);
    }

    private String snapshot(long commentId) {
        return jdbc.queryForObject(
                "SELECT author_alias FROM community_comments WHERE id = ?",
                String.class, commentId);
    }

    private int commentCountFor(long userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_comments WHERE author_user_id = ?",
                Integer.class, userId);
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'AL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 1 question (minimum eligibility shape). */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'ALB%s', 'bank')
                """.formatted(ownerId, seq++));
        long question = insert("""
                INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                VALUES (%d, 'ALU%s', 'ACTIVE', 1, %d)
                """.formatted(bank, seq++, ownerId));
        long version = insert("""
                INSERT INTO question_versions (question_id, version_number, question_type, content,
                                               difficulty, default_points, metadata, created_by)
                VALUES (%d, 1, 'SINGLE_CHOICE', 'content', 'MEDIUM', 1, '{}'::jsonb, %d)
                """.formatted(question, ownerId));
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points,
                                   duration_minutes, description)
                VALUES (%d, 'ALX%s', 'Alias fixture exam', 'PUBLISHED', now(), 1, 10, 'description')
                """.formatted(ownerId, seq++));
        long section = insert("INSERT INTO exam_sections (exam_id, title, position)"
                + " VALUES (%d, 'S1', 0)".formatted(examId));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'ALC1', 'SINGLE_CHOICE', 'content', 1, 0, '{}'::jsonb)
                """.formatted(examId, section, question, version));
        return examId;
    }

    /** POSTs a publication optionally carrying an authorAlias (UNPUBLISHED). */
    private long createUnpublished(long examId, String authorAlias) throws Exception {
        String aliasJson = authorAlias == null ? "" : ",\"authorAlias\":" + jsonString(authorAlias);
        MvcResult created = mockMvc.perform(post("/api/community/publications")
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"Alias probe\""
                                + aliasJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        return readTree(created).get("publicationId").asLong();
    }

    /** Created AND published (alias "Al-Alias") through the real service. */
    private long createPublished() {
        try {
            long examId = newPublishedExam(teacherId);
            long pubId = createUnpublished(examId, null);
            mockMvc.perform(patch("/api/community/publications/" + pubId)
                            .with(jwtSubject(teacherId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Al-Alias\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + pubId + "/publish")
                            .with(jwtSubject(teacherId)))
                    .andExpect(status().isOk());
            return pubId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
    }

    private static JsonNode readTree(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(result.getResponse().getContentAsString());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
