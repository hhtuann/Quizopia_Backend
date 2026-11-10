package com.quizopia.backend.community;

import com.quizopia.backend.community.exception.CommunityErrorCode;
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
 * HTTP-layer tests for the #65 rating interaction (ADR-007 §5/§6) on
 * PostgreSQL 17 Testcontainers: the fixed order (validation →
 * admission → permission → §4 visibility 404 → self-rating check BEFORE
 * the upsert → single-statement RETURNING (xmax = 0) upsert), PUT 201
 * first / 200 update with deterministic aggregates, DELETE 200 always
 * with {@code average:null} + {@code myRating:null} at zero ratings,
 * ROUND(AVG, 2) determinism, the publisher self-rating 409, bounds
 * validation and the auth matrix. Publications are seeded through the
 * real {@code CommunityService} (POST/PATCH/publish) — the #41
 * catalog-suite pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunitySocialRatingIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long publicationId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("srt", "TEACHER");
        studentId = newUser("srs", "STUDENT");
        publicationId = createPublished("Rating fixture publication");
    }

    // ==================================================================
    // §5/§6: PUT 201 first / 200 update; DELETE 200; aggregate determinism
    // ==================================================================

    @Test
    void firstRatingIs201UpdateIs200DeleteIs200WithDeterministicAggregates() throws Exception {
        MvcResult first = mockMvc.perform(putRating(publicationId, studentId, 4))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.average").value(4.0))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.myRating").value(4))
                .andReturn();
        assertThat(keySet(readTree(first)))
                .containsExactlyInAnyOrder("average", "ratingCount", "myRating");

        // Update: 200 — same row, new value; the aggregates recompute.
        mockMvc.perform(putRating(publicationId, studentId, 2))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.average").value(2.0))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.myRating").value(2));

        assertThat(ratingRowCount(publicationId)).isEqualTo(1);
        assertThat(ratingValue(studentId, publicationId)).isEqualTo(2);

        // DELETE: 200 always; zero ratings → average is JSON null
        // (never 0) and myRating is null.
        MvcResult deleted = mockMvc.perform(deleteRating(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.ratingCount").value(0))
                .andReturn();
        JsonNode deletedBody = readTree(deleted);
        assertThat(deletedBody.get("average").isNull())
                .as("average is JSON null at zero ratings (ADR-007 §3)")
                .isTrue();
        assertThat(deletedBody.get("myRating").isNull()).isTrue();

        assertThat(ratingRowCount(publicationId)).isZero();

        // Idempotent re-DELETE: still 200 with the same empty shape.
        mockMvc.perform(deleteRating(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(0));
    }

    @Test
    void averageRoundsToTwoDecimalsAcrossMultipleRaters() throws Exception {
        long rater2 = newUser("sra", "STUDENT");
        long rater3 = newUser("srb", "STUDENT");

        mockMvc.perform(putRating(publicationId, studentId, 1)).andExpect(status().isCreated());
        mockMvc.perform(putRating(publicationId, rater2, 1)).andExpect(status().isCreated());
        mockMvc.perform(putRating(publicationId, rater3, 2)).andExpect(status().isCreated());

        // AVG(1, 1, 2) = 1.333… → ROUND(..., 2) = 1.33.
        mockMvc.perform(putRating(publicationId, rater3, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average").value(1.0))
                .andExpect(jsonPath("$.ratingCount").value(3))
                .andExpect(jsonPath("$.myRating").value(1));

        mockMvc.perform(putRating(publicationId, rater3, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average").value(1.33))
                .andExpect(jsonPath("$.ratingCount").value(3))
                .andExpect(jsonPath("$.myRating").value(2));

        // AVG(1, 2, 2) = 5/3 → 1.67 (round half-up, 2dp).
        mockMvc.perform(putRating(publicationId, studentId, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.average").value(1.67))
                .andExpect(jsonPath("$.ratingCount").value(3))
                .andExpect(jsonPath("$.myRating").value(2));
    }

    // ==================================================================
    // self-rating check (§5/§6) — BEFORE the upsert, no row ever lands
    // ==================================================================

    @Test
    void publisherSelfRatingIs409AndNeverInsertsARow() throws Exception {
        mockMvc.perform(putRating(publicationId, teacherId, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(CommunityErrorCode.COMMUNITY_SELF_RATING_NOT_ALLOWED.code()));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_ratings WHERE publication_id = ?",
                Integer.class, publicationId)).isZero();

        // The publisher may still LIKE their own quiz (§9) — and a
        // different teacher rates fine on the same publication.
        long otherTeacher = newUser("sro", "TEACHER");
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/like")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isCreated());
        mockMvc.perform(putRating(publicationId, otherTeacher, 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.average").value(5.0));
    }

    // ==================================================================
    // §4 visibility 404 on PUT (no existence oracle)
    // ==================================================================

    @Test
    void putInvisibleOrAbsentPublicationIs404() throws Exception {
        long hidden = createPublished("Hidden rating source");
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                hidden);
        mockMvc.perform(putRating(hidden, studentId, 3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        mockMvc.perform(putRating(9_999_999L, studentId, 3))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_ratings WHERE user_id = ?", Integer.class, studentId))
                .as("no rating row landed for the caller on any 404 path")
                .isZero();
    }

    // ==================================================================
    // bounds validation + auth matrix (§5)
    // ==================================================================

    @Test
    void outOfBoundsOrMissingRatingIs400WithoutAdmittingOrInserting() throws Exception {
        for (int bad : new int[]{0, 6, -1, 99}) {
            mockMvc.perform(putRating(publicationId, studentId, bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR.code()));
        }
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"high\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_ratings WHERE user_id = ?", Integer.class, studentId))
                .as("out-of-bounds payloads never inserted a rating row")
                .isZero();
    }

    @Test
    void guestGets401RolelessGets403DisabledGets403DefenseInDepth() throws Exception {
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/community/publications/" + publicationId + "/rating"))
                .andExpect(status().isUnauthorized());

        long roleless = insertUser("srn");
        mockMvc.perform(putRating(publicationId, roleless, 3))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteRating(publicationId, roleless))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", studentId);
        mockMvc.perform(putRating(publicationId, studentId, 3))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteRating(publicationId, studentId))
                .andExpect(status().isForbidden());
    }

    // ==================================================================
    // fixtures (real-service seeding — #41 catalog pattern)
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder putRating(long publicationId, long userId, int rating) {
        return put("/api/community/publications/" + publicationId + "/rating")
                .with(jwtSubject(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":" + rating + "}");
    }

    private static RequestBuilder deleteRating(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/rating")
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SR', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 2 sections / 3 questions. */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'SRB%s', 'bank')
                """.formatted(ownerId, seq++));
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'SRU%s-%d', 'ACTIVE', 1, %d)
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
                VALUES (%d, 'SRX%s', 'Rating fixture exam', 'PUBLISHED', now(), 3, 30, 'description')
                """.formatted(ownerId, seq++));
        long s1 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S1', 0)"
                .formatted(examId));
        long s2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S2', 1)"
                .formatted(examId));
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                                source_question_version_id, question_code, question_type,
                                                content, default_points, position, metadata)
                    VALUES (%d, %d, %d, %d, 'SRC%d', 'SINGLE_CHOICE', 'content', 1, %d, '{}'::jsonb)
                    """.formatted(examId, i == 2 ? s2 : s1, questions[i], versions[i], i + 1,
                    i == 2 ? 0 : i));
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
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Sr-Alias\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + newPublicationId + "/publish")
                            .with(jwtSubject(teacherId)))
                    .andExpect(status().isOk());
            return newPublicationId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
    }

    private int ratingRowCount(long publicationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_ratings WHERE publication_id = ?",
                Integer.class, publicationId);
    }

    private int ratingValue(long userId, long publicationId) {
        return jdbc.queryForObject(
                "SELECT rating FROM community_ratings WHERE user_id = ? AND publication_id = ?",
                Integer.class, userId, publicationId);
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
