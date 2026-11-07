package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityVisibilityStore;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer integration tests for the #41 anonymous public discovery
 * routes (ADR-006 §4/§6/§7) on PostgreSQL 17 Testcontainers: the anonymous
 * GET method matrix, §4-visibility-filtered pagination counts (interleaved
 * hidden/unpublished/archived/disabled/mismatched fixtures), detail
 * anti-enumeration, bounded/escaped/parametrized search, tag AND
 * semantics, categoryId filter, size caps, /tags visibility-aware usage
 * counts, recursive JSON secret redaction on the real DTOs, and the
 * reusable {@link CommunityVisibilityStore#isVisible(long)} oracle
 * contract for #45/#47.
 *
 * <p>Publications are seeded through the real {@code CommunityService}
 * (POST/PATCH/publish over HTTP) on real published exams; ineligible
 * states that no #40 API can reach (HIDDEN needs #42 moderation,
 * archived exam, disabled owner, publisher mismatch) are applied with raw
 * SQL afterwards — the same pattern as the #40 suites. The exam fixture
 * carries answer-key/option poison markers and the users carry real
 * emails/usernames so the redaction assertions are non-vacuous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityPublicCatalogIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CommunityVisibilityStore visibilityStore;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static final String CATALOG = "/api/public/community/publications";
    private static final String CATEGORIES = "/api/public/community/categories";
    private static final String TAGS = "/api/public/community/tags";

    /** §7 exact allowlists (recursive redaction checks assert equality). */
    private static final Set<String> CARD_KEYS = Set.of(
            "publicationId", "title", "summary", "authorName", "categoryCode", "categoryTitle",
            "tags", "questionCount", "sectionCount", "durationMinutes", "publishedAt", "allowFork");
    private static final Set<String> DETAIL_KEYS = new HashSet<>(CARD_KEYS) {
        {
            add("updatedAt");
        }
    };
    /** Exact keys that must never appear anywhere in a public payload (ci). */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "answerKey", "answer_key", "isCorrect", "is_correct", "examId", "exam_id",
            "question", "questions", "option", "options", "email", "username", "phone",
            "publisherUserId", "publisher_user_id", "displayName", "display_name",
            "roles", "password", "passwordHash", "createdBy", "sourceExamId", "sectionId");

    private static long seq = System.nanoTime();

    private long teacherId;
    private long studentId;
    private long categoryId;
    private String teacherEmail;
    private String teacherUsername;

    @BeforeEach
    void setUp() {
        long raw = insertUser("pct");
        teacherId = raw;
        teacherUsername = "pct" + (seq - 1);
        teacherEmail = teacherUsername + "@t.example.com";
        grantRole(teacherId, "TEACHER");
        studentId = newUser("pcs", "STUDENT");
        categoryId = jdbc.queryForObject(
                "SELECT id FROM community_categories WHERE code = 'toan'", Long.class);
    }

    // ==================================================================
    // anonymous / authenticated method matrix (§7)
    // ==================================================================

    @Test
    void anonymousGetIs200WithNoStoreOnAllFourRoutes() throws Exception {
        long publicationId = createPublished("Route quiz", List.of(), null);

        mockMvc.perform(get(CATALOG))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].publicationId").value(publicationId))
                .andExpect(jsonPath("$.items[0].authorName").value("Sensei"));

        mockMvc.perform(get(CATALOG + "/" + publicationId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicationId").value(publicationId));

        MvcResult categories = mockMvc.perform(get(CATEGORIES))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode categoryArray = objectMapper.readTree(categories.getResponse().getContentAsString());
        assertThat(categoryArray.isArray()).isTrue();
        assertThat(categoryArray.size()).isGreaterThanOrEqualTo(15); // V26 matrix assertion: 15 unique
        for (JsonNode node : categoryArray) {
            assertThat(keySet(node)).containsExactlyInAnyOrderElementsOf(Set.of("id", "code", "title"));
        }
        // Order = position ASC (deterministic code tiebreak) — compare to the DB truth.
        List<String> expectedOrder = jdbc.queryForList(
                "SELECT code FROM community_categories ORDER BY position ASC, code ASC", String.class);
        List<String> actualOrder = new ArrayList<>();
        categoryArray.forEach(n -> actualOrder.add(n.get("code").asString()));
        assertThat(actualOrder).isEqualTo(expectedOrder);

        MvcResult tags = mockMvc.perform(get(TAGS))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode tagArray = objectMapper.readTree(tags.getResponse().getContentAsString());
        assertThat(tagArray.isArray()).isTrue();
        for (JsonNode node : tagArray) {
            assertThat(keySet(node)).containsExactlyInAnyOrderElementsOf(Set.of("label", "usageCount"));
        }
    }

    @Test
    void nonGetMethodsOnPublicPathsAreDeniedForAnonymous() throws Exception {
        String detail = CATALOG + "/1";
        for (String path : new String[] {CATALOG, detail, CATEGORIES, TAGS}) {
            assertThat(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus())
                    .as("POST %s anonymous → deny-by-default 401", path).isEqualTo(401);
            assertThat(mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus())
                    .as("PUT %s anonymous → deny-by-default 401", path).isEqualTo(401);
            assertThat(mockMvc.perform(delete(path)).andReturn().getResponse().getStatus())
                    .as("DELETE %s anonymous → deny-by-default 401", path).isEqualTo(401);
        }
        assertThat(mockMvc.perform(patch(detail).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus())
                .as("PATCH is not a mapped method on public paths").isEqualTo(401);
    }

    @Test
    void authenticatedStudentAndTeacherGet200OnPublicRoutes() throws Exception {
        createPublished("Auth matrix quiz", List.of(), null);
        mockMvc.perform(get(CATALOG).with(jwtSubject(studentId))).andExpect(status().isOk());
        mockMvc.perform(get(CATALOG).with(jwtSubject(teacherId))).andExpect(status().isOk());
        mockMvc.perform(get(CATEGORIES).with(jwtSubject(studentId))).andExpect(status().isOk());
        mockMvc.perform(get(TAGS).with(jwtSubject(teacherId))).andExpect(status().isOk());
    }

    // ==================================================================
    // §4-visibility interleaved pagination counts
    // ==================================================================

    @Test
    void interleavedEligibilityPaginationCountsAndPagesOnlyVisible() throws Exception {
        List<Long> visibleIds = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            visibleIds.add(createPublished("Visible quiz " + i, List.of(), null));
        }
        // Eight invisible fixtures, every §4 failure mode at least once.
        long hidden = createPublished("Hidden quiz", List.of(), null);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden);
        long unpublished = createPublication(newPublishedExam(teacherId), "Unpublished quiz");
        long archivedExam = createPublished("Archived-exam quiz", List.of(), null);
        jdbc.update("""
                UPDATE exams SET status = 'ARCHIVED'
                WHERE id = (SELECT exam_id FROM community_publications WHERE id = ?)
                """, archivedExam);
        long disabledTeacher = newUser("pdt", "TEACHER");
        long disabledOwner = createPublishedAs(disabledTeacher, "Disabled-owner quiz");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabledTeacher);
        long mismatched = createPublished("Publisher-mismatch quiz", List.of(), null);
        jdbc.update("UPDATE community_publications SET publisher_user_id = ? WHERE id = ?",
                newUser("pmt", "TEACHER"), mismatched);
        long hidden2 = createPublished("Hidden quiz 2", List.of(), null);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden2);
        long unpublished2 = createPublication(newPublishedExam(teacherId), "Unpublished quiz 2");
        entityManager.clear();

        MvcResult first = mockMvc.perform(get(CATALOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(7))
                .andReturn();
        JsonNode items = objectMapper.readTree(first.getResponse().getContentAsString()).get("items");
        // Deterministic published_at DESC, id DESC: newest created visible first.
        List<Long> returned = new ArrayList<>();
        items.forEach(n -> returned.add(n.get("publicationId").asLong()));
        List<Long> expected = new ArrayList<>(visibleIds);
        java.util.Collections.reverse(expected);
        assertThat(returned).isEqualTo(expected);

        // Page arithmetic over the same filtered set: size 3 → 3 pages (3+3+1).
        mockMvc.perform(get(CATALOG).param("size", "3").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.items.length()").value(3));
        mockMvc.perform(get(CATALOG).param("size", "3").param("page", "2"))
                .andExpect(jsonPath("$.items.length()").value(1));
        // Beyond the end: empty items, totals unchanged.
        mockMvc.perform(get(CATALOG).param("size", "3").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(7));
    }

    @Test
    void sizeIsCappedAt50AndNegativePageClampsToZero() throws Exception {
        for (int i = 0; i < 3; i++) {
            createPublished("Cap quiz " + i, List.of(), null);
        }
        mockMvc.perform(get(CATALOG).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items.length()").value(3));
        mockMvc.perform(get(CATALOG).param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1));
        mockMvc.perform(get(CATALOG).param("page", "-4").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    // ==================================================================
    // detail anti-enumeration (§7/§12.3)
    // ==================================================================
    @Test
    void detailReturnsIdentical404ForEveryIneligibleStateAndRandomIds() throws Exception {
        long visible = createPublished("Detail quiz", List.of(), null);
        long hidden = createPublished("Hidden detail", List.of(), null);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden);
        long unpublished = createPublication(newPublishedExam(teacherId), "Unpublished detail");
        long archived = createPublished("Archived detail", List.of(), null);
        jdbc.update("""
                UPDATE exams SET status = 'ARCHIVED'
                WHERE id = (SELECT exam_id FROM community_publications WHERE id = ?)
                """, archived);
        long disabledTeacher = newUser("pdd", "TEACHER");
        long disabled = createPublishedAs(disabledTeacher, "Disabled detail");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabledTeacher);
        long mismatch = createPublished("Mismatch detail", List.of(), null);
        jdbc.update("UPDATE community_publications SET publisher_user_id = ? WHERE id = ?",
                newUser("pmd", "TEACHER"), mismatch);
        entityManager.clear();

        MvcResult reference = mockMvc.perform(get(CATALOG + "/999999999"))
                .andExpect(status().isNotFound()).andReturn();
        String referenceBody = reference.getResponse().getContentAsString();
        String referenceCode = objectMapper.readTree(referenceBody).get("code").asString();

        for (long id : new long[] {hidden, unpublished, archived, disabled, mismatch, 0, -1, 999999998}) {
            MvcResult result = mockMvc.perform(get(CATALOG + "/" + id))
                    .andExpect(status().isNotFound()).andReturn();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("code").asString())
                    .as("publication %s is 404 with the random-id body", id)
                    .isEqualTo(referenceCode)
                    .isEqualTo("COMMUNITY_NOT_FOUND");
            assertThat(body.get("message").asString())
                    .isEqualTo(objectMapper.readTree(referenceBody).get("message").asString());
        }

        // Visible detail: exact §7 allowlist only.
        MvcResult detail = mockMvc.perform(get(CATALOG + "/" + visible))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode detailNode = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertThat(keySet(detailNode)).isEqualTo(DETAIL_KEYS);
        assertThat(detailNode.get("allowFork").isBoolean()).isTrue();
        assertThat(detailNode.get("updatedAt")).isNotNull();
    }

    // ==================================================================
    // recursive JSON secret redaction on real DTOs (§7/§12)
    // ==================================================================

    @Test
    void publicPayloadsNeverLeakAnswersOrIdentityMaterial() throws Exception {
        // The exam fixture poisons question content/answer keys; the user row
        // carries a real email/username/display name.
        createPublished("Redaction quiz", List.of("redacted-tag"), categoryId);

        MvcResult page = mockMvc.perform(get(CATALOG).param("q", "Redaction"))
                .andExpect(status().isOk()).andReturn();
        MvcResult detail = mockMvc.perform(get(CATALOG + "/" + objectMapper
                        .readTree(page.getResponse().getContentAsString())
                        .get("items").get(0).get("publicationId").asText()))
                .andExpect(status().isOk()).andReturn();
        MvcResult categories = mockMvc.perform(get(CATEGORIES)).andExpect(status().isOk()).andReturn();
        MvcResult tags = mockMvc.perform(get(TAGS)).andExpect(status().isOk()).andReturn();

        for (String raw : new String[] {
                page.getResponse().getContentAsString(),
                detail.getResponse().getContentAsString(),
                categories.getResponse().getContentAsString(),
                tags.getResponse().getContentAsString()}) {
            Set<String> keys = new HashSet<>();
            collectKeys(objectMapper.readTree(raw), keys);
            for (String key : keys) {
                assertThat(FORBIDDEN_KEYS)
                        .as("public payload key '%s' is not in the §7 allowlist", key)
                        .doesNotContain(key);
            }
            assertThat(raw)
                    .as("composition poison never serializes")
                    .doesNotContain("SECRET-CONTENT-MARKER")
                    .doesNotContain("expectedAnswer")
                    .doesNotContain("3.14")
                    .as("identity poison never serializes")
                    .doesNotContain(teacherEmail)
                    .doesNotContain(teacherUsername)
                    .doesNotContain("\"Display\"");
        }
    }

    // ==================================================================
    // search: bounded, escaped, parametrized (§7)
    // ==================================================================

    @Test
    void searchIsCaseInsensitiveSubstringMatch() throws Exception {
        createPublished("Calculus Basics", List.of(), null);
        createPublished("calculus ADVANCED", List.of(), null);
        createPublished("Algebra Notes", List.of(), null);

        MvcResult result = mockMvc.perform(get(CATALOG).param("q", "alGeBrA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items").get(0).get("title").asString()).isEqualTo("Algebra Notes");
        // Summary is searched too — all three fixtures share the fixture summary.
        mockMvc.perform(get(CATALOG).param("q", "physics"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void searchEscapesLikeWildcardsToLiterals() throws Exception {
        createPublished("Plain title A", List.of(), null);
        createPublished("Plain title B", List.of(), null);
        long percent = createPublished("100% Fun", List.of(), null);
        long underscore = createPublished("Drill_1", List.of(), null);

        // Unescaped, '%' would match everything and '_' any single char.
        mockMvc.perform(get(CATALOG).param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].publicationId").value(percent));
        mockMvc.perform(get(CATALOG).param("q", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].publicationId").value(underscore));
        // Mixed literals+wildcards: "0% Fu" is a literal substring of
        // "100% Fun"; "0% F_n" is NOT (the escaped underscore must not act
        // as a one-char wildcard over the 'u' — unescaped it would match).
        mockMvc.perform(get(CATALOG).param("q", "0% Fu"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].publicationId").value(percent));
        mockMvc.perform(get(CATALOG).param("q", "0% F_n"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchIsParametrizedAgainstSqlInjectionAndClampedTo100Chars() throws Exception {
        createPublished("Injection safe quiz", List.of("inj-tag"), null);
        // Long titles (110 chars) let an over-long q prove CLAMPING (not rejecting).
        String longTitle = "L".repeat(110);
        createPublished(longTitle, List.of(), null);

        mockMvc.perform(get(CATALOG).param("q", "'; DROP TABLE community_tags; --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get(CATALOG).param("q", "safe' OR '1'='1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // The tags table survived parametrization.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_tags WHERE label = 'inj-tag'", Integer.class))
                .isEqualTo(1);

        // q longer than 100 chars is clamped to its first 100: an exactly
        // 110-char title is still matched by its (110-char) query truncated
        // to the 100-char prefix.
        mockMvc.perform(get(CATALOG).param("q", longTitle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        // And a 150-char query of nothing-but-noise stays a safe empty 200.
        mockMvc.perform(get(CATALOG).param("q", "z".repeat(150)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ==================================================================
    // filters: tags AND semantics, categoryId equality (§7)
    // ==================================================================

    @Test
    void tagFilterUsesAndSemanticsAndOverFiveTagsIsProvablyEmpty() throws Exception {
        long both = createPublished("Both tags", List.of("logic", "easy"), null);
        createPublished("One tag", List.of("logic"), null);

        mockMvc.perform(get(CATALOG).param("tags", "logic"))
                .andExpect(jsonPath("$.totalElements").value(2));
        MvcResult and = mockMvc.perform(get(CATALOG).param("tags", "logic", "easy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(and.getResponse().getContentAsString())
                .get("items").get(0).get("publicationId").asLong()).isEqualTo(both);
        // Comma form binds the same list; uppercase is normalized to labels.
        mockMvc.perform(get(CATALOG).param("tags", "LOGIC,EASY"))
                .andExpect(jsonPath("$.totalElements").value(1));
        // Unknown tag filters to nothing; >5 distinct tags cannot match any
        // publication (max 5 tags each) → empty without probing.
        mockMvc.perform(get(CATALOG).param("tags", "logic", "ghost"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(CATALOG).param("tags", "a", "b", "c", "d", "e", "f"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void categoryIdFilterSelectsEqualityOnly() throws Exception {
        long categorized = createPublished("Categorized quiz", List.of(), categoryId);
        createPublished("Uncategorized quiz", List.of(), null);

        MvcResult result = mockMvc.perform(get(CATALOG).param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items").get(0).get("publicationId").asLong()).isEqualTo(categorized);
        mockMvc.perform(get(CATALOG).param("categoryId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ==================================================================
    // /tags: visibility-aware usage counts + ordering (§4/§7)
    // ==================================================================

    @Test
    void tagUsageCountsExcludeInvisibleSourcesAndOrderByUsageThenLabel() throws Exception {
        createPublished("Shared 1", List.of("shared", "alpha"), null);
        createPublished("Shared 2", List.of("shared"), null);
        createPublished("Solo", List.of("zeta"), null);

        long hidden = createPublished("Hidden tags", List.of("ghost"), null);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden);
        createPublication(newPublishedExam(teacherId), "Unpublished tags",
                List.of("phantom"), null);
        entityManager.clear();

        MvcResult result = mockMvc.perform(get(TAGS))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> labels = new ArrayList<>();
        array.forEach(n -> labels.add(n.get("label").asString()));

        assertThat(labels).as("usageCount DESC then label ASC").containsExactly("shared", "alpha", "zeta");
        assertThat(labels).as("invisible sources contribute no tags").doesNotContain("ghost", "phantom");
        assertThat(array.get(0).get("usageCount").asLong()).isEqualTo(2);
        assertThat(array.get(1).get("usageCount").asLong()).isEqualTo(1);
    }

    // ==================================================================
    // CommunityVisibilityStore.isVisible — the reusable oracle (#45/#47)
    // ==================================================================

    @Test
    void visibilityStoreIsVisibleEncodesTheSection4Predicate() {
        long visible = createPublished("Oracle quiz", List.of(), null);
        long hidden = createPublished("Oracle hidden", List.of(), null);
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden);
        long archived = createPublished("Oracle archived", List.of(), null);
        jdbc.update("""
                UPDATE exams SET status = 'ARCHIVED'
                WHERE id = (SELECT exam_id FROM community_publications WHERE id = ?)
                """, archived);
        long disabledTeacher = newUser("pod", "TEACHER");
        long disabled = createPublishedAs(disabledTeacher, "Oracle disabled");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", disabledTeacher);
        long mismatch = createPublished("Oracle mismatch", List.of(), null);
        jdbc.update("UPDATE community_publications SET publisher_user_id = ? WHERE id = ?",
                newUser("pom", "TEACHER"), mismatch);
        entityManager.clear();

        assertThat(visibilityStore.isVisible(visible)).isTrue();
        assertThat(visibilityStore.isVisible(hidden)).isFalse();
        assertThat(visibilityStore.isVisible(archived)).isFalse();
        assertThat(visibilityStore.isVisible(disabled)).isFalse();
        assertThat(visibilityStore.isVisible(mismatch)).isFalse();
        assertThat(visibilityStore.isVisible(999999999L)).isFalse();
    }

    // ==================================================================
    // fixtures + helpers (#40 patterns; seeding through the real service)
    // ==================================================================

    /** A fresh PUBLISHED exam with 2 sections / 3 questions (answer poison inside). */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'PQB%s', 'bank')
                """.formatted(ownerId, seq++));
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'PQU%s-%d', 'ACTIVE', 1, %d)
                    """.formatted(bank, seq++, i, ownerId));
            versions[i] = insert("""
                    INSERT INTO question_versions (question_id, version_number, question_type, content,
                                                   difficulty, default_points, metadata, created_by)
                    VALUES (%d, 1, 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 'MEDIUM', 1, '{}'::jsonb, %d)
                    """.formatted(questions[i], ownerId));
        }
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points,
                                   duration_minutes, description)
                VALUES (%d, 'PEX%s', 'Fixture exam', 'PUBLISHED', now(), 3, 30,
                        'A physics-flavored description')
                """.formatted(ownerId, seq++));
        long s1 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S1', 0)"
                .formatted(examId));
        long s2 = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (%d, 'S2', 1)"
                .formatted(examId));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'PQC1', 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 1, 0, '{}'::jsonb)
                """.formatted(examId, s1, questions[0], versions[0]));
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
                VALUES (%d, %d, %d, %d, 'PQC2', 'NUMERIC_FILL', 'SECRET-CONTENT-MARKER', 1, 1,
                        '{}'::jsonb, '{"expectedAnswer":"3.14"}'::jsonb)
                """.formatted(examId, s1, questions[1], numericVersion));
        jdbc.update("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, position, metadata)
                VALUES (%d, %d, %d, %d, 'PQC3', 'SINGLE_CHOICE', 'SECRET-CONTENT-MARKER', 1, 0, '{}'::jsonb)
                """.formatted(examId, s2, questions[2], versions[2]));
        return examId;
    }

    /** POSTs a publication for a fresh exam owned by the teacher (real service). */
    private long createPublication(long examId, String title, List<String> tags, Long categoryId) {
        return createPublicationAs(teacherId, examId, title, tags, categoryId);
    }

    private long createPublicationAs(long userId, long examId, String title,
            List<String> tags, Long categoryId) {
        try {
            StringBuilder body = new StringBuilder("{\"examId\":" + examId
                    + ",\"title\":\"" + title + "\",\"summary\":\"physics summary\"");
            if (categoryId != null) {
                body.append(",\"categoryId\":").append(categoryId);
            }
            if (tags != null && !tags.isEmpty()) {
                body.append(",\"tags\":[").append(tags.stream()
                        .map(t -> "\"" + t + "\"").reduce((a, b) -> a + "," + b).orElse(""))
                        .append("]");
            }
            body.append("}");
            MvcResult result = mockMvc.perform(post("/api/community/publications")
                            .with(jwtSubject(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.toString()))
                    .andExpect(status().isCreated())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("publicationId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("fixture publication create failed", ex);
        }
    }

    private long createPublication(long examId, String title) {
        return createPublication(examId, title, null, null);
    }

    private long createPublishedAs(long userId, String title) {
        return createPublishedAs(userId, title, null, null);
    }

    /** Created AND published (alias set) through the real service. */
    private long createPublished(String title, List<String> tags, Long categoryId) {
        return createPublishedAs(teacherId, title, tags, categoryId);
    }

    private long createPublishedAs(long userId, String title, List<String> tags, Long categoryId) {
        try {
            long examId = newPublishedExam(userId);
            long publicationId = createPublicationAs(userId, examId, title, tags, categoryId);
            mockMvc.perform(patch("/api/community/publications/" + publicationId)
                            .with(jwtSubject(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Sensei\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/community/publications/" + publicationId + "/publish")
                            .with(jwtSubject(userId)))
                    .andExpect(status().isOk());
            return publicationId;
        } catch (Exception ex) {
            throw new IllegalStateException("fixture published publication failed", ex);
        }
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

    private static Set<String> keySet(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.properties().forEach(p -> keys.add(p.getKey()));
        return keys;
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
