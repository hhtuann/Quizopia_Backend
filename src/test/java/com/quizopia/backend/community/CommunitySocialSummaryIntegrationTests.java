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
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
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
 * HTTP-layer tests for the #65 social-summary twins (ADR-007 §5) on
 * PostgreSQL 17 Testcontainers: the anonymous public route renders
 * aggregates ONLY (no caller-state keys anywhere, recursive redaction
 * check), the authenticated twin adds exactly the caller's own
 * {@code liked}/{@code myRating} (never another user's), commentCount
 * counts VISIBLE comments only (the public-list predicate), ineligible
 * publications are byte-identical 404s on both routes, the public
 * route's non-GET method matrix stays deny-by-default, and both carry
 * no-store. Publications are seeded through the real
 * {@code CommunityService}; comments are seeded with raw SQL (the
 * comment API lands with #66).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunitySocialSummaryIntegrationTests {

    private static final String PUBLIC_SUMMARY =
            "/api/public/community/publications/%d/social-summary";
    private static final String AUTHED_SUMMARY =
            "/api/community/publications/%d/social-summary";

    /** Exact allowlists (recursive redaction checks assert equality). */
    private static final Set<String> PUBLIC_KEYS = Set.of(
            "likeCount", "average", "ratingCount", "commentCount");
    private static final Set<String> AUTHED_KEYS = Set.of(
            "likeCount", "average", "ratingCount", "commentCount", "liked", "myRating");
    /** Caller-state / identity keys that must never appear in a public payload. */
    private static final Set<String> FORBIDDEN_PUBLIC_KEYS = Set.of(
            "liked", "myRating", "userId", "user_id", "authorUserId", "author_user_id",
            "publisherUserId", "publisher_user_id", "username", "email", "displayName");

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
        teacherId = newUser("sst", "TEACHER");
        studentId = newUser("sss", "STUDENT");
        publicationId = createPublished("Summary fixture publication");
    }

    // ==================================================================
    // public twin: aggregates only, never caller state
    // ==================================================================

    @Test
    void guestGetsCountsOnlyWithNoCallerStateKeysAnywhere() throws Exception {
        seedInteractions();

        MvcResult result = mockMvc.perform(get(PUBLIC_SUMMARY.formatted(publicationId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.likeCount").value(2))
                .andExpect(jsonPath("$.ratingCount").value(2))
                .andExpect(jsonPath("$.average").value(3.0))
                .andExpect(jsonPath("$.commentCount").value(2))
                .andReturn();

        JsonNode body = readTree(result);
        assertThat(keySet(body))
                .as("public summary is exactly the four aggregates")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_KEYS);

        // Recursive redaction: no caller-state or identity key at any
        // depth (the payload is flat, but the check holds regardless).
        Set<String> deep = new LinkedHashSet<>();
        collectKeys(body, deep);
        assertThat(deep).doesNotContainAnyElementsOf(FORBIDDEN_PUBLIC_KEYS);

        // An authenticated caller sees the same counts-only shape on
        // the public route (caller state lives on the twin only).
        MvcResult authed = mockMvc.perform(get(PUBLIC_SUMMARY.formatted(publicationId))
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(keySet(readTree(authed))).containsExactlyInAnyOrderElementsOf(PUBLIC_KEYS);
    }

    @Test
    void publicSummaryRendersNullAverageAtZeroRatings() throws Exception {
        MvcResult result = mockMvc.perform(get(PUBLIC_SUMMARY.formatted(publicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(0))
                .andExpect(jsonPath("$.ratingCount").value(0))
                .andExpect(jsonPath("$.commentCount").value(0))
                .andReturn();
        JsonNode body = readTree(result);
        assertThat(body.get("average").isNull())
                .as("average is JSON null (never 0) at zero ratings (§3)")
                .isTrue();
    }

    // ==================================================================
    // authenticated twin: aggregates + the caller's own state ONLY
    // ==================================================================

    @Test
    void authenticatedTwinAddsExactlyTheCallersOwnStates() throws Exception {
        long other = newUser("sso", "STUDENT");
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/like")
                        .with(jwtSubject(other)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .with(jwtSubject(other)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5}"))
                .andExpect(status().isCreated());
        seedInteractions(); // student likes + rates 2; 2 visible comments

        MvcResult mine = mockMvc.perform(get(AUTHED_SUMMARY.formatted(publicationId))
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.likeCount").value(3))
                .andExpect(jsonPath("$.ratingCount").value(3))
                .andExpect(jsonPath("$.average").value(3.67))
                .andExpect(jsonPath("$.commentCount").value(2))
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.myRating").value(2))
                .andReturn();
        assertThat(keySet(readTree(mine))).containsExactlyInAnyOrderElementsOf(AUTHED_KEYS);

        // Another caller with NO interactions: same aggregates, their
        // own states false/null — never the other user's.
        long third = newUser("ssth", "STUDENT");
        MvcResult theirs = mockMvc.perform(get(AUTHED_SUMMARY.formatted(publicationId))
                        .with(jwtSubject(third)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false))
                .andReturn();
        JsonNode theirsBody = readTree(theirs);
        assertThat(theirsBody.get("myRating").isNull()).isTrue();
    }

    @Test
    void commentCountSharesThePublicListPredicateVisibleOnly() throws Exception {
        // 2 VISIBLE + 1 HIDDEN + 1 DELETED comment rows (raw SQL — the
        // comment API lands with #66): only VISIBLE counts, so the
        // summary total can never diverge from the future public list.
        insertComment("VISIBLE", "visible one");
        insertComment("VISIBLE", "visible two");
        insertComment("HIDDEN", "hidden body");
        insertComment("DELETED", "");

        mockMvc.perform(get(PUBLIC_SUMMARY.formatted(publicationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(2));
        mockMvc.perform(get(AUTHED_SUMMARY.formatted(publicationId))
                        .with(jwtSubject(studentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commentCount").value(2));
    }

    // ==================================================================
    // §4 anti-enumeration: identical 404s on both twins
    // ==================================================================

    @Test
    void ineligiblePublicationIsA404OnBothRoutesByteIdenticalPerRoute() throws Exception {
        long hidden = createPublished("Hidden summary source");
        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                hidden);

        // Both twins answer 404 COMMUNITY_NOT_FOUND for ineligible ids.
        for (long id : new long[]{hidden, 9_999_999L}) {
            mockMvc.perform(get(PUBLIC_SUMMARY.formatted(id)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
            mockMvc.perform(get(AUTHED_SUMMARY.formatted(id))
                            .with(jwtSubject(studentId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        }

        // Anti-enumeration parity per route (ADR-007 §5): the 404 body
        // for a hidden publication is identical to a random absent id
        // in every deterministic field — status, code, message — no
        // existence oracle. (The envelope's per-request timestamp and
        // path are excluded: path echoes the caller's own URL and the
        // timestamp is generated per response.)
        assertThat(errorBody(mockMvc.perform(get(PUBLIC_SUMMARY.formatted(hidden)))
                .andExpect(status().isNotFound()).andReturn()))
                .as("public route: hidden and absent 404s differ in no deterministic field")
                .isEqualTo(errorBody(mockMvc.perform(get(PUBLIC_SUMMARY.formatted(9_999_999L)))
                        .andExpect(status().isNotFound()).andReturn()));

        assertThat(errorBody(mockMvc.perform(get(AUTHED_SUMMARY.formatted(hidden))
                        .with(jwtSubject(studentId)))
                .andExpect(status().isNotFound()).andReturn()))
                .as("authenticated route: hidden and absent 404s differ in no deterministic field")
                .isEqualTo(errorBody(mockMvc.perform(get(AUTHED_SUMMARY.formatted(9_999_999L))
                                .with(jwtSubject(studentId)))
                        .andExpect(status().isNotFound()).andReturn()));
    }

    /** The error envelope minus the per-request timestamp and path. */
    private static java.util.Map<String, Object> errorBody(MvcResult result) throws Exception {
        JsonNode root = readTree(result);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        root.properties().forEach(property -> {
            if (!"timestamp".equals(property.getKey()) && !"path".equals(property.getKey())) {
                out.put(property.getKey(), property.getValue().asString());
            }
        });
        return out;
    }

    // ==================================================================
    // method matrix on the public route (deny-by-default)
    // ==================================================================

    @Test
    void nonGetMethodsOnThePublicSummaryPathAreDeniedForAnonymous() throws Exception {
        String path = PUBLIC_SUMMARY.formatted(publicationId);
        assertThat(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus())
                .as("POST the public summary path → deny-by-default 401").isEqualTo(401);
        assertThat(mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus())
                .as("PUT the public summary path → deny-by-default 401").isEqualTo(401);
        assertThat(mockMvc.perform(delete(path)).andReturn().getResponse().getStatus())
                .as("DELETE the public summary path → deny-by-default 401").isEqualTo(401);
        assertThat(mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus())
                .as("PATCH is not a mapped method on public paths").isEqualTo(401);
    }

    @Test
    void authenticatedSummaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get(AUTHED_SUMMARY.formatted(publicationId)))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // fixtures (real-service seeding — #41 catalog pattern)
    // ==================================================================

    /** The student likes + rates 2; a foreign teacher rates 4. */
    private void seedInteractions() throws Exception {
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/like")
                        .with(jwtSubject(studentId)))
                .andExpect(status().isCreated());
        long foreignTeacher = newUser("ssf", "TEACHER");
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/like")
                        .with(jwtSubject(foreignTeacher)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .with(jwtSubject(studentId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/community/publications/" + publicationId + "/rating")
                        .with(jwtSubject(foreignTeacher)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4}"))
                .andExpect(status().isCreated());
        insertComment("VISIBLE", "visible one");
        insertComment("VISIBLE", "visible two");
        insertComment("HIDDEN", "hidden body");
    }

    private void insertComment(String state, String body) {
        jdbc.update("""
                INSERT INTO community_comments (publication_id, author_user_id, author_alias, body, state)
                VALUES (?, ?, 'SummaryAlias', ?, ?)
                """, publicationId, studentId, body, state);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'SS', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    /** A fresh PUBLISHED exam with 2 sections / 3 questions. */
    private long newPublishedExam(long ownerId) {
        long bank = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name)
                VALUES (%d, 'SSB%s', 'bank')
                """.formatted(ownerId, seq++));
        long[] questions = new long[3];
        long[] versions = new long[3];
        for (int i = 0; i < 3; i++) {
            questions[i] = insert("""
                    INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by)
                    VALUES (%d, 'SSU%s-%d', 'ACTIVE', 1, %d)
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
                VALUES (%d, 'SSX%s', 'Summary fixture exam', 'PUBLISHED', now(), 3, 30, 'description')
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
                    VALUES (%d, %d, %d, %d, 'SSC%d', 'SINGLE_CHOICE', 'content', 1, %d, '{}'::jsonb)
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
                            .content("{\"expectedVersion\":0,\"authorAlias\":\"Ss-Alias\"}"))
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
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> keySet(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.properties().forEach(property -> keys.add(property.getKey()));
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

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
