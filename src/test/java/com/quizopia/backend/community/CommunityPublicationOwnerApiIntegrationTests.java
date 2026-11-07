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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer integration tests for the #40 owner lifecycle endpoints
 * (ADR-006 §4/§7) on PostgreSQL 17 Testcontainers: the §7 auth matrix
 * (guest/student/foreign/no-permission/dual-role/unverified), create
 * eligibility, PATCH semantics + optimistic conflicts, publish/unpublish
 * transitions incl. idempotency + HIDDEN, and the no-answers/no-PII
 * serialization guarantee.
 *
 * <p>Uses the {@code jwt()} test post-processor (bypasses the AuthN
 * converter, which has its own tests) so service-layer authorization is
 * what's exercised; the AuthN-layer disabled/unverified outcomes are
 * covered by {@code CommunityAuthNIntegrationTests} with real tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityPublicationOwnerApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long foreignTeacherId;
    private long categoryId;

    @BeforeEach
    void setUp() {
        teacherId = newUser("ct", "TEACHER");
        studentId = newUser("cs", "STUDENT");
        foreignTeacherId = newUser("cft", "TEACHER");
        categoryId = jdbc.queryForObject(
                "SELECT id FROM community_categories WHERE code = 'toan'", Long.class);
    }

    // ==================================================================
    // §7 auth matrix
    // ==================================================================

    @Test
    void guestGets401OnEveryOwnerRoute() throws Exception {
        mockMvc.perform(get("/api/community/me/publications")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/community/publications")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(1L)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/community/publications/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/community/publications/1/publish")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/community/publications/1/unpublish")).andExpect(status().isUnauthorized());
    }

    @Test
    void studentGets403AccessDenied() throws Exception {
        mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(studentId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(studentId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    @Test
    void foreignTeacherGets404AntiEnumerationButOwnListIs200Empty() throws Exception {
        long publicationId = createPublication();

        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(foreignTeacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"title\":\"hijack\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(foreignTeacherId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/unpublish")
                        .with(jwtSubject(foreignTeacherId)))
                .andExpect(status().isNotFound());
        // Own-scoped list stays 200 (empty — publications of the caller only).
        mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(foreignTeacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void teacherWithoutCommunityPublishPermissionGets403() throws Exception {
        jdbc.update("""
                DELETE FROM role_permissions rp
                USING roles r, permissions p
                WHERE rp.role_id = r.id AND rp.permission_id = p.id
                  AND r.code = 'TEACHER' AND p.code = 'COMMUNITY_PUBLISH'
                """);
        long examId = newPublishedExam(teacherId, null);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    @Test
    void unverifiedTeacherIsDeniedByTheServiceLayerDefenseInDepth() throws Exception {
        long unverified = insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   token_version, failed_login_attempts, password_changed_at)
                VALUES ('cuv%s', 'cuv%s@t.example.com', 'h', 'UV', 'ACTIVE', 0, 0, now())
                """.formatted(seq, seq++));
        grantRole(unverified, "TEACHER");
        long examId = newPublishedExam(unverified, null);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(unverified))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    @Test
    void dualRoleUserCanCreate() throws Exception {
        grantRole(teacherId, "STUDENT");
        long examId = newPublishedExam(teacherId, 45);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isCreated());
    }

    // ==================================================================
    // create eligibility (§4)
    // ==================================================================

    @Test
    void createRejectsNonPublishedExam() throws Exception {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, total_points)
                VALUES (%d, 'CEX%s', 'Draft exam', 'DRAFT', 0)
                """.formatted(teacherId, seq++));
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_PUBLISH_INELIGIBLE"));
    }

    @Test
    void createRejectsMissingAndForeignExamsWith404() throws Exception {
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(999999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        long foreignExam = newPublishedExam(foreignTeacherId, null);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(foreignExam)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
    }

    @Test
    void createRejectsDuplicatePublicationWith409() throws Exception {
        long examId = newPublishedExam(teacherId, null);
        createPublication(examId);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_PUBLICATION_EXISTS"));
    }

    @Test
    void createRejectsUnknownCategoryAndInvalidTags() throws Exception {
        long examId = newPublishedExam(teacherId, null);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId
                                + ",\"title\":\"T\",\"categoryId\":999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_CATEGORY_NOT_FOUND"));

        // Payload tags are lower-cased/trimmed per ADR §3; only tags that are
        // still invalid AFTER normalization are rejected (charset, >5).
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"T\",\"tags\":[\"bad_tag!\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_TAG_INVALID"));

        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"T\","
                                + "\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_TAG_INVALID"));
    }

    @Test
    void createDenormalizesCountsNormalizesTagsAndDefaults() throws Exception {
        long examId = newPublishedExam(teacherId, 45); // 2 sections, 3 questions
        MvcResult result = mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"title\":\"  My Quiz  \","
                                + "\"summary\":\"s\",\"categoryId\":" + categoryId + ","
                                + "\"tags\":[\"Grade-10\",\"grade-10\",\" Math \"],"
                                + "\"reusePolicy\":\"ALLOW_FORK\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("UNPUBLISHED"))
                .andExpect(jsonPath("$.moderationState").value("VISIBLE"))
                .andExpect(jsonPath("$.reusePolicy").value("ALLOW_FORK"))
                .andExpect(jsonPath("$.questionCount").value(3))
                .andExpect(jsonPath("$.sectionCount").value(2))
                .andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.publishedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tags.length()").value(2)) // Grade-10→grade-10 dedup
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("title").asString()).isEqualTo("My Quiz");
        assertThat(body.get("tags").toString()).contains("grade-10").contains("math");

        // Rows are linkage-driven; exam untouched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_publication_tags pt"
                        + " JOIN community_publications p ON p.id = pt.publication_id"
                        + " WHERE p.exam_id = ?", Integer.class, examId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_tags WHERE label IN ('grade-10', 'math')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void createWithNullDurationDenormalizesZero() throws Exception {
        long examId = newPublishedExam(teacherId, null);
        mockMvc.perform(post("/api/community/publications").with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(examId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(0));
    }

    // ==================================================================
    // PATCH semantics (§4)
    // ==================================================================

    @Test
    void patchRejectsVersionMismatchWith409() throws Exception {
        long publicationId = createPublication();
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":99,\"title\":\"new\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_CONCURRENT_MODIFICATION"));
    }

    @Test
    void patchAppliesPatchableSetAndBumpsVersion() throws Exception {
        long publicationId = createPublication();
        MvcResult result = mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"title\":\"New title\",\"summary\":\"new summary\","
                                + "\"authorAlias\":\"Cô Lan\",\"reusePolicy\":\"ALLOW_FORK\","
                                + "\"tags\":[\"fresh\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.summary").value("new summary"))
                .andExpect(jsonPath("$.authorAlias").value("Cô Lan"))
                .andExpect(jsonPath("$.reusePolicy").value("ALLOW_FORK"))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long examId = body.get("examId").asLong();

        // Immutable identity columns untouched (no UPDATE path exists).
        assertThat(jdbc.queryForMap(
                "SELECT exam_id, publisher_user_id FROM community_publications WHERE id = ?",
                publicationId))
                .containsEntry("exam_id", examId)
                .containsEntry("publisher_user_id", teacherId);
    }

    @Test
    void patchLeavesOmittedFieldsUnchanged() throws Exception {
        long publicationId = createPublication();
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Community fixture quiz"))
                .andExpect(jsonPath("$.reusePolicy").value("NO_FORK"))
                .andExpect(jsonPath("$.version").value(0)); // nothing dirty → no UPDATE, version unchanged
    }

    @Test
    void patchRejectsAliasEqualToAccountIdentity() throws Exception {
        long publicationId = createPublication();
        String email = jdbc.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, teacherId);
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"authorAlias\":\"" + email + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_INVALID"));
    }

    // ==================================================================
    // publish / unpublish (§4)
    // ==================================================================

    @Test
    void publishRequiresAlias() throws Exception {
        long publicationId = createPublication();
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ALIAS_REQUIRED"));
    }

    @Test
    void publishSetsStateReDerivesCountsAndIsIdempotent() throws Exception {
        long examId = newPublishedExam(teacherId, 30);
        long publicationId = createPublication(examId);
        // Composition drift between create and publish (allowed by the test
        // fixtures only to prove re-derivation): +2 questions.
        // +2 rows with fresh distinct source questions (unique constraint).
        for (int i = 0; i < 2; i++) {
            long extraQ = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES ((SELECT question_bank_id FROM questions LIMIT 1), 'QUX%s-%d', 'ACTIVE', 1, %d)
                    """.formatted(seq++, i, teacherId));
            long extraV = insert("""
                    INSERT INTO question_versions (question_id, version_number, question_type, content,
                                                   difficulty, default_points, metadata, created_by)
                    VALUES (%d, 1, 'SINGLE_CHOICE', 'extra', 'MEDIUM', 1, '{}'::jsonb, %d)
                    """.formatted(extraQ, teacherId));
            jdbc.update("""
                    INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                                source_question_version_id, question_code, question_type,
                                                content, default_points, position, metadata)
                    VALUES (%d, (SELECT id FROM exam_sections WHERE exam_id = %d LIMIT 1), %d, %d,
                            'QC-X', 'SINGLE_CHOICE', 'extra', 1, %d, '{}'::jsonb)
                    """.formatted(examId, examId, extraQ, extraV, 9 + i));
        }

        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"authorAlias\":\"Sensei\"}"))
                .andExpect(status().isOk());

        MvcResult first = mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.questionCount").value(5)) // re-derived
                .andExpect(jsonPath("$.sectionCount").value(2))
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("publishedAt")).isNotNull();
        int versionAfterPublish = firstBody.get("version").asInt();

        // Idempotent no-op: same version, same publishedAt, no error.
        MvcResult second = mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn();
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("version").asInt()).isEqualTo(versionAfterPublish);
        assertThat(secondBody.get("publishedAt").asString())
                .isEqualTo(firstBody.get("publishedAt").asString());
    }

    @Test
    void publishFromHiddenIs409EvenWhileUnpublished() throws Exception {
        long publicationId = createPublication();
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        entityManager.clear(); // raw JDBC update bypassed the shared persistence context
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_MODERATION_HIDDEN"));
    }

    @Test
    void publishRechecksExamEligibility() throws Exception {
        long examId = newPublishedExam(teacherId, null);
        long publicationId = createPublication(examId);
        jdbc.update("UPDATE exams SET status = 'ARCHIVED' WHERE id = ?", examId);
        entityManager.clear();
        mockMvc.perform(patch("/api/community/publications/" + publicationId)
                        .with(jwtSubject(teacherId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"authorAlias\":\"Sensei\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_PUBLISH_INELIGIBLE"));
    }

    @Test
    void unpublishTransitionsAndIsIdempotentAndSurvivesHidden() throws Exception {
        long publicationId = createPublishedPublication();

        mockMvc.perform(post("/api/community/publications/" + publicationId + "/unpublish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("UNPUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").value(org.hamcrest.Matchers.nullValue()));

        MvcResult firstNoop = mockMvc.perform(
                        post("/api/community/publications/" + publicationId + "/unpublish")
                                .with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andReturn();
        int versionAfterFirstUnpublish = objectMapper
                .readTree(firstNoop.getResponse().getContentAsString()).get("version").asInt();

        // A moderator hide does not block unpublish (hide survives, §4).
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        entityManager.clear(); // raw JDBC update bypassed the shared persistence context
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/unpublish")
                        .with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationState").value("HIDDEN"))
                .andExpect(jsonPath("$.version").value(versionAfterFirstUnpublish));
    }

    // ==================================================================
    // owner list
    // ==================================================================

    @Test
    void myListIsOwnScopedOrderedAndValidatesSort() throws Exception {
        createPublication();
        long examId = newPublishedExam(teacherId, null);
        createPublication(examId);

        mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(teacherId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/community/me/publications")
                        .with(jwtSubject(teacherId)).param("sort", "createdAt,asc"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/community/me/publications")
                        .with(jwtSubject(teacherId)).param("sort", "passwordHash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_VALIDATION_ERROR"));
    }

    // ==================================================================
    // no-answers / no-PII serialization guarantee (§7/§12)
    // ==================================================================

    @Test
    void ownerDtoNeverLeaksAnswersOrIdentityKeys() throws Exception {
        // Composition carries distinctive answer/content markers.
        long publicationId = createPublishedPublication();
        MvcResult list = mockMvc.perform(get("/api/community/me/publications").with(jwtSubject(teacherId)))
                .andExpect(status().isOk()).andReturn();
        // Idempotent publish no-op: full DTO without needing the version.
        MvcResult publishNoop = mockMvc.perform(
                        post("/api/community/publications/" + publicationId + "/publish")
                                .with(jwtSubject(teacherId)))
                .andExpect(status().isOk()).andReturn();

        for (String raw : new String[] {
                list.getResponse().getContentAsString(),
                publishNoop.getResponse().getContentAsString()}) {
            Set<String> keys = new HashSet<>();
            collectKeys(objectMapper.readTree(raw), keys);
            for (String key : keys) {
                String lower = key.toLowerCase();
                assertThat(lower)
                        .as("owner DTO key '%s' must not leak answer/PII material", key)
                        .doesNotContain("answer")
                        .doesNotContain("correct")
                        .doesNotContain("option")
                        .doesNotContain("content")
                        .doesNotContain("explanation")
                        .doesNotContain("email")
                        .doesNotContain("username")
                        .doesNotContain("phone")
                        .doesNotContain("password")
                        .doesNotContain("display")
                        .doesNotContain("role");
            }
            assertThat(raw)
                    .as("composition markers never serialize")
                    .doesNotContain("SECRET-CONTENT-MARKER")
                    .doesNotContain("expectedAnswer");
        }
    }

    // ==================================================================
    // fixtures + helpers
    // ==================================================================

    /** (owner JWT) — a fresh PUBLISHED exam with 2 sections / 3 questions. */
    private long newPublishedExam(long ownerId, Integer durationMinutes) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'QB%s', 'bank')
                """.formatted(ownerId, seq++));
        // Three DISTINCT source questions (uk_exam_questions_exam_source is
        // UNIQUE(exam_id, source_question_id)).
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'QU%s-%d', 'ACTIVE', 1, %d)
                    """.formatted(bank, seq++, i, ownerId));
            versions[i] = insert("""
                    INSERT INTO question_versions (question_id, version_number, question_type, content,
                                                   difficulty, default_points, metadata, created_by)
                    VALUES (%d, 1, 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 'MEDIUM', 1, '{}'::jsonb, %d)
                    """.formatted(questions[i], ownerId));
        }
        long version = versions[0];
        long question = questions[0];
        String duration = durationMinutes == null ? "NULL" : String.valueOf(durationMinutes);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points,
                                   duration_minutes)
                VALUES (%d, 'EX%s', 'Fixture exam', 'PUBLISHED', now(), 3, %s)
                """.formatted(ownerId, seq++, duration));
        long s1 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S1', 0)"
                .formatted(examId));
        long s2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S2', 1)"
                .formatted(examId));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'QC1', 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 1, 0, '{}'::jsonb)
                """.formatted(examId, s1, question, version));
        // One NUMERIC_FILL row whose answer_key carries an answer marker.
        long numericVersion = insert("""
                INSERT INTO question_versions (question_id, version_number, question_type, content,
                                               difficulty, default_points, metadata, created_by, answer_key)
                VALUES (%d, 2, 'NUMERIC_FILL', 'SECRET-CONTENT-MARKER', 'MEDIUM', 1, '{}'::jsonb, %d,
                        '{"expectedAnswer":"3.14"}'::jsonb)
                """.formatted(questions[1], ownerId));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata, answer_key)
                VALUES (%d, %d, %d, %d, 'QC2', 'NUMERIC_FILL', 'SECRET-CONTENT-MARKER', 1, 1,
                        '{}'::jsonb, '{"expectedAnswer":"3.14"}'::jsonb)
                """.formatted(examId, s1, questions[1], numericVersion));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'QC3', 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 1, 0, '{}'::jsonb)
                """.formatted(examId, s2, questions[2], versions[2]));
        return examId;
    }

    /** POSTs a publication for a fresh PUBLISHED exam owned by the teacher. */
    private long createPublication() {
        return createPublication(newPublishedExam(teacherId, null));
    }

    private long createPublication(long examId) {
        try {
            MvcResult result = mockMvc.perform(post("/api/community/publications")
                            .with(jwtSubject(teacherId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(examId)))
                    .andExpect(status().isCreated())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("publicationId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("fixture publication create failed", ex);
        }
    }

    /** A publication created AND published (alias set) for the teacher. */
    private long createPublishedPublication() {
        try {
            long examId = newPublishedExam(teacherId, 30);
            long publicationId = createPublication(examId);
            mockMvc.perform(patch("/api/community/publications/" + publicationId)
                            .with(jwtSubject(teacherId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Sensei\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                            .with(jwtSubject(teacherId)))
                    .andExpect(status().isOk());
            return publicationId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
    }

    private static String createBody(long examId) {
        return "{\"examId\":" + examId + ",\"title\":\"Community fixture quiz\"}";
    }

    private long newUser(String prefix, String role) {
        long userId = insertUser(prefix);
        grantRole(userId, role);
        return userId;
    }

    private long insertUser(String prefix) {
        return insert("""
                INSERT INTO users (username, email, password_hash, display_name, status,
                                   email_verified_at, token_version, failed_login_attempts,
                                   password_changed_at)
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'Display', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private void grantRole(long userId, String roleCode) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                userId, roleCode);
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    /** Recursively collects every JSON object key (serialization redaction check). */
    private static void collectKeys(JsonNode node, Set<String> keys) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            JsonNode current = stack.pop();
            if (current.isObject()) {
                current.properties().forEach(property -> {
                    keys.add(property.getKey());
                    stack.push(property.getValue());
                });
            } else if (current.isArray()) {
                current.forEach(stack::push);
            }
        }
    }
}
