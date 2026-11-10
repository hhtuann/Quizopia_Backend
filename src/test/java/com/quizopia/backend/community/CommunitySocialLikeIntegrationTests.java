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
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the #65 like interaction (ADR-007 §5/§6) on
 * PostgreSQL 17 Testcontainers: the §5/§6 order (admission → permission
 * → §4 visibility 404 → idempotent upsert), PUT 201/200 + DELETE 200
 * always with correct likeCount aggregates after each step, the auth
 * matrix (guest/roleless/disabled; STUDENT and TEACHER both hold
 * COMMUNITY_LIKE), publisher self-like allowed (ADR-007 §9), §4
 * anti-enumeration on invisible/absent publications with admission
 * still consumed, and no-store everywhere.
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
class CommunitySocialLikeIntegrationTests {

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
        teacherId = newUser("slt", "TEACHER");
        studentId = newUser("sls", "STUDENT");
        publicationId = createPublished("Like fixture publication");
    }

    // ==================================================================
    // §5/§6: PUT 201 / idempotent 200; DELETE 200 always; aggregates
    // ==================================================================

    @Test
    void studentLikesTwiceThenUnlikesTwiceWithExactlyOneRowAndAggregateCodes() throws Exception {
        mockMvc.perform(putLike(publicationId, studentId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.liked").value(true));

        // Idempotent repeat: 200, one row, aggregate unchanged. The §5
        // body is exactly {likeCount, liked}.
        MvcResult repeat = mockMvc.perform(putLike(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.liked").value(true))
                .andReturn();
        assertThat(keySet(readTree(repeat))).containsExactlyInAnyOrder("likeCount", "liked");

        assertThat(likeCount(publicationId)).isEqualTo(1);
        assertThat(likedAt(publicationId, studentId)).isNotNull();

        mockMvc.perform(deleteLike(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.liked").value(false));
        assertThat(likeCount(publicationId)).isZero();

        // DELETE of an absent like is a normal 200 — idempotent.
        mockMvc.perform(deleteLike(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.liked").value(false));
        assertThat(likeCount(publicationId)).isZero();
    }

    @Test
    void publisherStudentTeacherAndForeignTeacherEachOwnOneLikeRow() throws Exception {
        // STUDENT and TEACHER both hold COMMUNITY_LIKE; the PUBLISHER
        // may like their own quiz too (ADR-007 §9 — only self-RATING is
        // blocked).
        mockMvc.perform(putLike(publicationId, studentId)).andExpect(status().isCreated());
        mockMvc.perform(putLike(publicationId, teacherId)).andExpect(status().isCreated());
        long foreignTeacher = newUser("slf", "TEACHER");
        mockMvc.perform(putLike(publicationId, foreignTeacher)).andExpect(status().isCreated());

        assertThat(likeCount(publicationId)).isEqualTo(3);

        // One user's DELETE never touches another user's like.
        mockMvc.perform(deleteLike(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(2));
        assertThat(likeCount(publicationId)).isEqualTo(2);
    }

    // ==================================================================
    // §4 visibility 404 on PUT (no existence oracle) — admission still ran
    // ==================================================================

    @Test
    void putInvisibleOrAbsentPublicationIs404AndConsumesAdmission() throws Exception {
        long hidden = createPublished("Hidden source");
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                hidden);
        mockMvc.perform(putLike(hidden, studentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        long unpublished = createPublished("Unpublished source");
        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?",
                unpublished);
        mockMvc.perform(putLike(unpublished, studentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        mockMvc.perform(putLike(9_999_999L, studentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_likes WHERE user_id = ?", Integer.class, studentId))
                .as("no like row landed for the caller on any 404 path")
                .isZero();
        // §5: every syntactically valid request admitted FIRST — the
        // 404s still consumed the user's LIKE bucket.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(CommunityRateLimiter.Policy.LIKE, studentId)))
                .isEqualTo(1);
    }

    @Test
    void unlikeWorksWhateverBecameOfTheSource() throws Exception {
        // DELETE skips the §4 probe (bookmark-DELETE precedent): the
        // like is removed even after the source went hidden/absent —
        // 200 always, no existence oracle.
        mockMvc.perform(putLike(publicationId, studentId)).andExpect(status().isCreated());
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        mockMvc.perform(deleteLike(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false));
        assertThat(likeCount(publicationId)).isZero();

        mockMvc.perform(deleteLike(9_999_999L, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false));
    }

    // ==================================================================
    // auth matrix (§5)
    // ==================================================================

    @Test
    void guestGets401RolelessGets403DisabledGets403DefenseInDepth() throws Exception {
        // Guest: the like routes sit under /api/community/** → 401.
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/like"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/community/publications/" + publicationId + "/like"))
                .andExpect(status().isUnauthorized());

        // Authenticated but without COMMUNITY_LIKE: 403.
        long roleless = insertUser("sln");
        mockMvc.perform(putLike(publicationId, roleless))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteLike(publicationId, roleless))
                .andExpect(status().isForbidden());

        // DISABLED: real AuthN rejects the token (401 — covered with a
        // real token in CommunitySocialAuthNIntegrationTests); with a
        // forged JWT the service-layer recheck is defense-in-depth 403.
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", studentId);
        mockMvc.perform(putLike(publicationId, studentId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteLike(publicationId, studentId))
                .andExpect(status().isForbidden());
    }

    // ==================================================================
    // fixtures (real-service seeding — #41 catalog pattern)
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder putLike(long publicationId, long userId) {
        return put("/api/community/publications/" + publicationId + "/like")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder deleteLike(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/like")
                .with(jwtSubject(userId));
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SL', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 2 sections / 3 questions. */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'SLB%s', 'bank')
                """.formatted(ownerId, seq++));
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'SLU%s-%d', 'ACTIVE', 1, %d)
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
                VALUES (%d, 'SLX%s', 'Like fixture exam', 'PUBLISHED', now(), 3, 30, 'description')
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
                    VALUES (%d, %d, %d, %d, 'SLC%d', 'SINGLE_CHOICE', 'content', 1, %d, '{}'::jsonb)
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
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Sl-Alias\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + newPublicationId + "/publish")
                            .with(jwtSubject(teacherId)))
                    .andExpect(status().isOk());
            return newPublicationId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
    }

    private int likeCount(long publicationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_likes WHERE publication_id = ?",
                Integer.class, publicationId);
    }

    private java.sql.Timestamp likedAt(long publicationId, long userId) {
        return jdbc.queryForObject("""
                SELECT created_at FROM community_likes
                WHERE publication_id = ? AND user_id = ?
                """, java.sql.Timestamp.class, publicationId, userId);
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
