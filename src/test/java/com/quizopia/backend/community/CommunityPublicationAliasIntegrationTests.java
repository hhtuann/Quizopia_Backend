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
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The publication authorAlias / legacy member-alias cross-namespace
 * probe (ADR-007 §4, #66; surviving unchanged under Rev 4 / #72):
 * publish/PATCH {@code authorAlias} is rejected ci-equal to any
 * {@code community_member_profiles.alias} row. After the Rev 4
 * display-name policy retired the member alias lifecycle, that table is
 * a retained read-only legacy surface (V32) — the probe keeps the
 * publisher credit namespace closed against every alias a member ever
 * took historically. The former comment-side alias consent tests
 * (capture, change, impersonation, collision 409) were removed with
 * the lifecycle itself: comments now derive their author name
 * server-side from {@code users.display_name} (see
 * {@code CommunityCommentIntegrationTests}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class CommunityPublicationAliasIntegrationTests {

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
    // publication reverse-check (§4, the single P3-A touch)
    // ==================================================================

    @Test
    void publicationAuthorAliasRejectedWhenEqualToAnyMemberAliasCi() throws Exception {
        // A LEGACY member-profile row owns this alias (seeded directly —
        // after Rev 4 no API path writes member profiles; V32 keeps the
        // table read-only for exactly this cross-check).
        String memberAlias = "Taken-Alias-" + seq;
        jdbc.update("INSERT INTO community_member_profiles (user_id, alias) VALUES (?, ?)",
                studentId, memberAlias);

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
    // fixtures
    // ==================================================================

    private static RequestPostProcessor jwtSubject(long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
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

    // Non-@Transactional suite: committed fixtures would leak into the
    // shared container and break the exact-count catalog suites (which
    // run earlier in the default run order). Only the rows this class
    // created (created_at >= class load) are removed; publication
    // children CASCADE along.
    private static final java.time.Instant CLASS_START = java.time.Instant.now();

    @org.junit.jupiter.api.AfterEach
    void cleanCommittedFixtures() {
        jdbc.update("DELETE FROM community_publications WHERE created_at >= ?", java.sql.Timestamp.from(CLASS_START));
        jdbc.update("DELETE FROM community_member_profiles WHERE created_at >= ?", java.sql.Timestamp.from(CLASS_START));
    }

}
