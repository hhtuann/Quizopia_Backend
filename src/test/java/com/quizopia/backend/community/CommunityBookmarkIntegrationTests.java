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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the #45 bookmark shelf (ADR-006 §7) on
 * PostgreSQL 17 Testcontainers: the §7/§9 order (admission → permission
 * → §4 visibility 404 → idempotent upsert), PUT 201/200 + DELETE 200
 * idempotency, the auth matrix (guest/roleless/disabled), the
 * unavailable placeholders ({publicationId, savedAt, unavailable:true}
 * ONLY — no last-known content, recursive field-set equality) across
 * every §4 failure mode (hidden, unpublished, archived exam, disabled
 * owner), anti-enumeration on the public detail route, and pagination
 * ({@code created_at DESC, id DESC}, empty pages beyond the end).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityBookmarkIntegrationTests {

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
        teacherId = newUser("bkt", "TEACHER");
        studentId = newUser("bks", "STUDENT");
        publicationId = createPublishedPublication("Bookmark fixture publication");
    }

    // ==================================================================
    // §7 order: PUT 201 / idempotent 200; DELETE 200 always
    // ==================================================================

    @Test
    void studentSavesTwiceThenDeletesTwiceWithExactlyOneRowAndIdempotentCodes() throws Exception {
        MvcResult first = mockMvc.perform(putBookmark(publicationId, studentId))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.savedAt").exists())
                .andReturn();

        // Idempotent repeat: 200 with the SAME row (same savedAt).
        mockMvc.perform(putBookmark(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.savedAt").value(extract(first, "savedAt")));

        assertThat(bookmarkCount(publicationId)).isEqualTo(1);

        mockMvc.perform(deleteBookmark(publicationId, studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(bookmarkCount(publicationId)).isZero();

        // DELETE of an absent bookmark is a normal 200 — idempotent.
        mockMvc.perform(deleteBookmark(publicationId, studentId))
                .andExpect(status().isOk());
        assertThat(bookmarkCount(publicationId)).isZero();
    }

    @Test
    void teacherForeignTeacherAndStudentEachOwnAnIndependentShelf() throws Exception {
        // STUDENT and TEACHER both hold COMMUNITY_BOOKMARK; bookmarks
        // are reader-facing, so the publisher AND a foreign teacher
        // each save their own row — three collections, no interference.
        mockMvc.perform(putBookmark(publicationId, studentId)).andExpect(status().isCreated());
        mockMvc.perform(putBookmark(publicationId, teacherId)).andExpect(status().isCreated());
        long foreignTeacher = newUser("bkf", "TEACHER");
        mockMvc.perform(putBookmark(publicationId, foreignTeacher)).andExpect(status().isCreated());

        assertThat(bookmarkCount(publicationId)).isEqualTo(3);

        // One user's DELETE never touches another user's shelf.
        mockMvc.perform(deleteBookmark(publicationId, studentId)).andExpect(status().isOk());
        assertThat(bookmarkCount(publicationId)).isEqualTo(2);

        MvcResult list = mockMvc.perform(getMyBookmarks(teacherId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(itemsByPublicationId(list)).containsKey(publicationId);
    }

    // ==================================================================
    // §4 visibility 404 on PUT (no existence oracle) — admission still ran
    // ==================================================================

    @Test
    void putInvisibleOrAbsentPublicationIs404AndConsumesAdmission() throws Exception {
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        mockMvc.perform(putBookmark(publicationId, studentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        long unpublished = createPublishedPublication("Unpublished source");
        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?", unpublished);
        mockMvc.perform(putBookmark(unpublished, studentId)).andExpect(status().isNotFound());

        mockMvc.perform(putBookmark(9_999_999L, studentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM community_bookmarks", Integer.class))
                .isZero();
        // §9: every syntactically valid request admitted FIRST — the
        // 404s still consumed the user's BOOKMARK bucket.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(
                        CommunityRateLimiter.Policy.BOOKMARK, studentId)))
                .isEqualTo(1);
    }

    // ==================================================================
    // auth matrix (§7)
    // ==================================================================

    @Test
    void guestGets401RolelessGets403DisabledGets403DefenseInDepth() throws Exception {
        // Guest: all three routes are caught by the chain (401).
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/bookmark"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/community/publications/" + publicationId + "/bookmark"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/community/me/bookmarks"))
                .andExpect(status().isUnauthorized());

        // Authenticated but without COMMUNITY_BOOKMARK: 403.
        long roleless = insertUser("bkn");
        mockMvc.perform(putBookmark(publicationId, roleless))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteBookmark(publicationId, roleless))
                .andExpect(status().isForbidden());
        mockMvc.perform(getMyBookmarks(roleless))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));

        // DISABLED: real AuthN rejects the token (401 — covered with a
        // real token in CommunityAuthNIntegrationTests); with a forged
        // JWT the service-layer recheck is defense-in-depth 403.
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", studentId);
        mockMvc.perform(putBookmark(publicationId, studentId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        mockMvc.perform(deleteBookmark(publicationId, studentId))
                .andExpect(status().isForbidden());
        mockMvc.perform(getMyBookmarks(studentId))
                .andExpect(status().isForbidden());
    }

    // ==================================================================
    // unavailable placeholders: exactly three fields, no §4 bypass
    // ==================================================================

    @Test
    void sourcesFailingThePredicateListAsThreeFieldPlaceholdersOnly() throws Exception {
        long hidden = createPublishedPublication("Hidden source");
        long unpublished = createPublishedPublication("Unpublished source");
        long archivedExam = createPublishedPublication("Archived-exam source");
        // On a SECOND teacher: disabling that publisher must not take
        // the other sources (or `stillVisible`) down with it.
        long otherTeacher = newUser("bko", "TEACHER");
        long disabledOwnerSource = createPublishedPublication("Disabled-owner source", otherTeacher);
        long stillVisible = createPublishedPublication("Still visible source");

        for (long id : new long[]{hidden, unpublished, archivedExam, disabledOwnerSource, stillVisible}) {
            mockMvc.perform(putBookmark(id, studentId)).andExpect(status().isCreated());
        }

        // Every §4 failure mode, applied AFTER saving (the shelf keeps
        // the row; the card must not leak).
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?", hidden);
        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?", unpublished);
        jdbc.update("""
                UPDATE exams SET status = 'ARCHIVED'
                WHERE id = (SELECT exam_id FROM community_publications WHERE id = ?)
                """, archivedExam);
        jdbc.update("""
                UPDATE users SET status = 'DISABLED'
                WHERE id = (SELECT publisher_user_id FROM community_publications WHERE id = ?)
                """, disabledOwnerSource);

        MvcResult list = mockMvc.perform(getMyBookmarks(studentId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andReturn();

        Map<Long, JsonNode> byPublication = itemsByPublicationId(list);
        assertThat(byPublication).containsOnlyKeys(
                hidden, unpublished, archivedExam, disabledOwnerSource, stillVisible);

        for (long unavailableId : new long[]{
                hidden, unpublished, archivedExam, disabledOwnerSource}) {
            JsonNode entry = byPublication.get(unavailableId);
            assertThat(entry.get("unavailable").asBoolean()).isTrue();
            assertThat(keySet(entry))
                    .as("unavailable entry for publication %d carries exactly the three fields",
                            unavailableId)
                    .containsExactlyInAnyOrder("publicationId", "savedAt", "unavailable");
            // Recursive redaction: nothing below the entry either.
            Set<String> deep = new LinkedHashSet<>();
            collectKeys(entry, deep);
            assertThat(deep).doesNotContain(
                    "title", "summary", "authorName", "categoryCode", "categoryTitle",
                    "tags", "questionCount", "sectionCount", "durationMinutes",
                    "publishedAt", "allowFork");
        }

        // The available entry is the full card + savedAt + false.
        JsonNode available = byPublication.get(stillVisible);
        assertThat(available.get("unavailable").asBoolean()).isFalse();
        assertThat(keySet(available)).containsExactlyInAnyOrder(
                "publicationId", "title", "summary", "authorName", "categoryCode",
                "categoryTitle", "tags", "questionCount", "sectionCount", "durationMinutes",
                "publishedAt", "allowFork", "savedAt", "unavailable");
        assertThat(available.get("title").asString()).isEqualTo("Still visible source");
        assertThat(available.get("authorName").asString()).isEqualTo("Bk-Alias");
        assertThat(available.get("questionCount").asInt()).isEqualTo(3);
        assertThat(available.get("tags").toString()).contains("grade-10").contains("physics");

        // Bookmarks never grant read rights: the public detail of every
        // now-invisible source is anti-enumeration 404 — even for the
        // user holding the bookmark.
        for (long unavailableId : new long[]{
                hidden, unpublished, archivedExam, disabledOwnerSource}) {
            mockMvc.perform(get("/api/public/community/publications/" + unavailableId)
                            .with(jwtSubject(studentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        }
        mockMvc.perform(get("/api/public/community/publications/" + stillVisible)
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk());
    }

    // ==================================================================
    // pagination: created_at DESC, id DESC; empty beyond the end
    // ==================================================================

    @Test
    void listIsOrderedSavedAtDescThenIdDescAndEmptyBeyondTheEnd() throws Exception {
        long oldest = createPublishedPublication("Oldest save");
        long tieLow = createPublishedPublication("Tie (lower id)");
        long tieHigh = createPublishedPublication("Tie (higher id)");
        for (long id : new long[]{oldest, tieLow, tieHigh}) {
            mockMvc.perform(putBookmark(id, studentId)).andExpect(status().isCreated());
        }
        // Stagger created_at: oldest save 3h ago, the two ties share
        // the same newer instant — the tie must break by id DESC.
        jdbc.update("UPDATE community_bookmarks SET created_at = now() - interval '3 hours'"
                + " WHERE publication_id = " + oldest + " AND user_id = " + studentId);
        jdbc.update("UPDATE community_bookmarks SET created_at = now() - interval '1 hour'"
                + " WHERE publication_id IN (" + tieLow + "," + tieHigh + ") AND user_id = " + studentId);

        MvcResult page0 = mockMvc.perform(getMyBookmarks(studentId, 0, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andReturn();
        assertThat(publicationOrder(page0)).containsExactly(tieHigh, tieLow);

        MvcResult page1 = mockMvc.perform(getMyBookmarks(studentId, 1, 2))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(publicationOrder(page1)).containsExactly(oldest);

        // Beyond the end: an empty page, totals unchanged.
        mockMvc.perform(getMyBookmarks(studentId, 5, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private static RequestBuilder putBookmark(long publicationId, long userId) {
        return put("/api/community/publications/" + publicationId + "/bookmark")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder deleteBookmark(long publicationId, long userId) {
        return delete("/api/community/publications/" + publicationId + "/bookmark")
                .with(jwtSubject(userId));
    }

    private static RequestBuilder getMyBookmarks(long userId) {
        return getMyBookmarks(userId, 0, 20);
    }

    private static RequestBuilder getMyBookmarks(long userId, int page, int size) {
        return get("/api/community/me/bookmarks")
                .with(jwtSubject(userId))
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .accept(MediaType.APPLICATION_JSON);
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'BK', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A PUBLISHED + VISIBLE publication owned by the default teacher. */
    private long createPublishedPublication(String title) {
        return createPublishedPublication(title, teacherId);
    }

    /** A PUBLISHED + VISIBLE publication (the §4 predicate passes). */
    private long createPublishedPublication(String title, long ownerId) {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'BKE%s', 'Bookmark fixture exam', 'PUBLISHED', now(), 1)
                """.formatted(ownerId, seq++));
        long publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, question_count,
                                                    section_count, duration_minutes, published_at)
                VALUES (%d, %d, '%s', 'Bk-Alias', 'PUBLISHED', 'VISIBLE', 3, 2, 15, now())
                """.formatted(examId, ownerId, title));
        // A category + two tags so the available card mapping is
        // non-vacuous.
        jdbc.update("""
                UPDATE community_publications SET category_id =
                    (SELECT id FROM community_categories ORDER BY id LIMIT 1)
                WHERE id = ?
                """, publicationId);
        for (String label : new String[]{"grade-10", "physics"}) {
            jdbc.update("INSERT INTO community_tags (label) VALUES (?) ON CONFLICT DO NOTHING", label);
            jdbc.update("""
                    INSERT INTO community_publication_tags (publication_id, tag_id)
                    SELECT ?, id FROM community_tags WHERE label = ?
                    """, publicationId, label);
        }
        return publicationId;
    }

    private int bookmarkCount(long publicationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM community_bookmarks WHERE publication_id = ?",
                Integer.class, publicationId);
    }

    private static String extract(MvcResult result, String field) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get(field).asText();
    }

    private static List<JsonNode> items(MvcResult result) throws Exception {
        JsonNode items = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get("items");
        List<JsonNode> out = new java.util.ArrayList<>();
        items.forEach(out::add);
        return out;
    }

    private static Map<Long, JsonNode> itemsByPublicationId(MvcResult result) throws Exception {
        return items(result).stream().collect(Collectors.toMap(
                item -> item.get("publicationId").asLong(), Function.identity()));
    }

    private static List<Long> publicationOrder(MvcResult result) throws Exception {
        return items(result).stream()
                .map(item -> item.get("publicationId").asLong())
                .toList();
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
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
