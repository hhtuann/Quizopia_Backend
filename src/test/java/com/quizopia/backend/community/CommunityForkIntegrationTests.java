package com.quizopia.backend.community;

import com.quizopia.backend.community.application.CommunityRateLimiter;
import com.quizopia.backend.question.application.QuestionService;
import com.quizopia.backend.question.dto.UpdateQuestionRequest;
import com.quizopia.backend.question.dto.UpdateQuestionRequest.OptionPart;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the #47 cross-user fork (ADR-006 §7-§8) on
 * PostgreSQL 17 Testcontainers: the happy-path clone (independent
 * bank/questions/version-1/options owned by the requester, four
 * question types compared field-by-field against the source
 * composition, tf_matrix_scoring copied, provenance REMAPPED to the
 * cloned ids), target edit+publish through the EXISTING exam APIs,
 * source immutability against post-publish question edits, zero
 * session/attempt/grade copying, sequential idempotency (200 replay /
 * 409 conflict), the §7 denied matrix, and Idempotency-Key validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class CommunityForkIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityRateLimiter rateLimiter;
    @Autowired
    private QuestionService questionService;

    private static long seq = System.nanoTime();

    private long publisherId;
    private long forkerId;
    private long sourceBankId;
    private long sourceExamId;
    private long publicationId;
    /** source question ids by type, for mutation and remap checks. */
    private Map<String, Long> sourceQuestionIds;

    @BeforeEach
    void setUp() {
        publisherId = newUser("fkt", "TEACHER");
        forkerId = newUser("fkf", "TEACHER");
        sourceBankId = insertSourceContent();
        sourceExamId = insertSourceExam();
        publicationId = insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, reuse_policy,
                                                    question_count, section_count, duration_minutes,
                                                    published_at)
                VALUES (%d, %d, 'Fork fixture publication', 'Fk-Alias', 'PUBLISHED', 'VISIBLE',
                        'ALLOW_FORK', 4, 2, 45, now())
                """.formatted(sourceExamId, publisherId));
    }

    // ==================================================================
    // Happy path: 201 + full independent clone, four types, remap
    // ==================================================================

    @Test
    void forkClonesAllFourTypesIntoIndependentBankAndDraftExam() throws Exception {
        MvcResult created = mockMvc.perform(fork(publicationId, forkerId, "happy-key-0001"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        JsonNode body = readTree(created);
        // §7 response shape EXACTLY: nothing beyond the allowlist (no
        // answers, no option content, no internal ids beyond the target).
        assertThat(keySet(body)).containsExactlyInAnyOrder(
                "targetExamId", "sourcePublicationId", "sourceTitle", "authorAlias", "forkedAt");
        assertThat(body.get("sourcePublicationId").asLong()).isEqualTo(publicationId);
        assertThat(body.get("sourceTitle").asString()).isEqualTo("Fork fixture publication");
        assertThat(body.get("authorAlias").asString()).isEqualTo("Fk-Alias");

        long targetExamId = body.get("targetExamId").asLong();
        assertThat(targetExamId).isNotEqualTo(sourceExamId);

        // --- target exam row: DRAFT, owned by the requester, provenance
        // --- and settings copied, tf_matrix_scoring deep-copied.
        Map<String, Object> target = jdbc.queryForMap(
                "SELECT * FROM exams WHERE id = ?", targetExamId);
        assertThat(target.get("status")).isEqualTo("DRAFT");
        assertThat(target.get("created_by_user_id")).isEqualTo(forkerId);
        assertThat(target.get("source_exam_id")).isEqualTo(sourceExamId);
        assertThat(target.get("title")).isEqualTo("Fork fixture exam");
        assertThat(target.get("description")).isEqualTo("Source description");
        assertThat(target.get("instructions")).isEqualTo("Read carefully");
        assertThat(((Number) target.get("duration_minutes")).intValue()).isEqualTo(45);
        assertThat(((Number) target.get("total_points")).intValue()).isZero();
        assertThat(target.get("subject_id"))
                .isEqualTo(jdbc.queryForObject("SELECT subject_id FROM exams WHERE id = ?",
                        Long.class, sourceExamId));
        assertThat(target.get("purpose_id"))
                .isEqualTo(jdbc.queryForObject("SELECT purpose_id FROM exams WHERE id = ?",
                        Long.class, sourceExamId));
        String sourceScoring = jdbc.queryForObject(
                "SELECT tf_matrix_scoring::text FROM exams WHERE id = ?", String.class, sourceExamId);
        assertThat(target.get("tf_matrix_scoring").toString().replaceAll("\\s+", ""))
                .as("tf_matrix_scoring deep-copied")
                .isEqualTo(sourceScoring == null ? "" : sourceScoring.replaceAll("\\s+", ""));
        assertThat((String) target.get("code"))
                .as("fresh EX code, not the source's")
                .isNotEqualTo(jdbc.queryForObject(
                        "SELECT code FROM exams WHERE id = ?", String.class, sourceExamId));

        // --- one fresh bank owned by the forker, named after the source.
        List<Map<String, Object>> banks = jdbc.queryForList(
                "SELECT * FROM question_banks WHERE created_by_user_id = ? ORDER BY id", forkerId);
        assertThat(banks).hasSize(1);
        long newBankId = (Long) banks.get(0).get("id");
        assertThat(banks.get(0).get("name")).isEqualTo("Fork fixture exam (Fork)");
        assertThat(banks.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(banks.get(0).get("visibility")).isEqualTo("PRIVATE");
        assertThat(banks.get(0).get("id")).isNotEqualTo(sourceBankId);

        // --- 4 cloned questions: ACTIVE, version 1, owned by forker.
        List<Map<String, Object>> clonedQuestions = jdbc.queryForList("""
                SELECT q.*, (SELECT count(*) FROM question_versions v
                             WHERE v.question_id = q.id) AS version_count
                FROM questions q WHERE q.question_bank_id = ? ORDER BY q.id
                """, newBankId);
        assertThat(clonedQuestions).hasSize(4);
        Set<Long> clonedQuestionIds = new LinkedHashSet<>();
        Set<Long> clonedVersionIds = new LinkedHashSet<>();
        for (Map<String, Object> q : clonedQuestions) {
            assertThat(q.get("status")).as("cloned question status").isEqualTo("ACTIVE");
            assertThat(((Number) q.get("current_version_number")).intValue()).isEqualTo(1);
            assertThat(q.get("created_by")).isEqualTo(forkerId);
            assertThat(((Number) q.get("version_count")).intValue()).isEqualTo(1);
            clonedQuestionIds.add((Long) q.get("id"));
            clonedVersionIds.add(jdbc.queryForObject(
                    "SELECT id FROM question_versions WHERE question_id = ? AND version_number = 1",
                    Long.class, (Long) q.get("id")));
        }
        assertThat(clonedQuestionIds).as("remapped ids differ from source").noneMatch(sourceQuestionIds::containsValue);

        // --- target composition: sections copied; every source question
        // --- field equals the source exam_questions row; provenance
        // --- points at the CLONED question/version pair.
        List<Map<String, Object>> sourceSections = jdbc.queryForList("""
                SELECT * FROM exam_sections WHERE exam_id = ? ORDER BY position
                """, sourceExamId);
        List<Map<String, Object>> targetSections = jdbc.queryForList("""
                SELECT * FROM exam_sections WHERE exam_id = ? ORDER BY position
                """, targetExamId);
        assertThat(targetSections).hasSameSizeAs(sourceSections);
        for (int i = 0; i < sourceSections.size(); i++) {
            assertThat(targetSections.get(i).get("title")).isEqualTo(sourceSections.get(i).get("title"));
            assertThat(targetSections.get(i).get("instructions"))
                    .isEqualTo(sourceSections.get(i).get("instructions"));
            assertThat(targetSections.get(i).get("code")).isEqualTo(sourceSections.get(i).get("code"));
            assertThat(targetSections.get(i).get("position")).isEqualTo(sourceSections.get(i).get("position"));
        }

        List<Map<String, Object>> sourceQuestions = jdbc.queryForList("""
                SELECT eq.*, es.position AS section_position
                FROM exam_questions eq JOIN exam_sections es ON es.id = eq.exam_section_id
                WHERE eq.exam_id = ? ORDER BY es.position, eq.position
                """, sourceExamId);
        List<Map<String, Object>> targetQuestions = jdbc.queryForList("""
                SELECT eq.*, es.position AS section_position
                FROM exam_questions eq JOIN exam_sections es ON es.id = eq.exam_section_id
                WHERE eq.exam_id = ? ORDER BY es.position, eq.position
                """, targetExamId);
        assertThat(targetQuestions).hasSameSizeAs(sourceQuestions);

        for (int i = 0; i < sourceQuestions.size(); i++) {
            Map<String, Object> sq = sourceQuestions.get(i);
            Map<String, Object> tq = targetQuestions.get(i);
            assertThat(tq.get("question_type")).isEqualTo(sq.get("question_type"));
            assertThat(tq.get("content")).as("content of %s", sq.get("question_type")).isEqualTo(sq.get("content"));
            assertThat(((Number) tq.get("default_points")).doubleValue())
                    .isEqualTo(((Number) sq.get("default_points")).doubleValue());
            assertThat(tq.get("difficulty")).isEqualTo(sq.get("difficulty"));
            assertThat(tq.get("explanation")).isEqualTo(sq.get("explanation"));
            assertThat(jsonText(tq, "answer_key")).isEqualTo(jsonText(sq, "answer_key"));
            assertThat(jsonText(tq, "metadata")).isEqualTo(jsonText(sq, "metadata"));
            assertThat(tq.get("position")).isEqualTo(sq.get("position"));
            assertThat(tq.get("section_position")).isEqualTo(sq.get("section_position"));

            // Provenance remap: the CLONED pair, not the source pair.
            assertThat(clonedQuestionIds).contains((Long) tq.get("source_question_id"));
            assertThat(clonedVersionIds).contains((Long) tq.get("source_question_version_id"));
            assertThat(jdbc.queryForObject(
                    "SELECT question_id FROM question_versions WHERE id = ?",
                    Long.class, (Long) tq.get("source_question_version_id")))
                    .as("pinned version belongs to the pinned cloned question")
                    .isEqualTo(tq.get("source_question_id"));

            // Cloned version content == source snapshot content.
            Map<String, Object> clonedVersion = jdbc.queryForMap(
                    "SELECT * FROM question_versions WHERE id = ?",
                    (Long) tq.get("source_question_version_id"));
            assertThat(clonedVersion.get("question_type")).isEqualTo(sq.get("question_type"));
            assertThat(clonedVersion.get("content")).isEqualTo(sq.get("content"));
            assertThat(clonedVersion.get("explanation")).isEqualTo(sq.get("explanation"));
            assertThat(clonedVersion.get("difficulty")).isEqualTo(sq.get("difficulty"));
            assertThat(((Number) clonedVersion.get("default_points")).doubleValue())
                    .isEqualTo(((Number) sq.get("default_points")).doubleValue());
            assertThat(jsonText(clonedVersion, "answer_key")).isEqualTo(jsonText(sq, "answer_key"));
            assertThat(jsonText(clonedVersion, "metadata")).isEqualTo(jsonText(sq, "metadata"));
            assertThat(((Number) clonedVersion.get("version_number")).intValue()).isEqualTo(1);
            assertThat(clonedVersion.get("created_by")).isEqualTo(forkerId);

            // Options: precise per-option comparison, cloned version AND
            // target exam snapshot AND (by construction) is_correct.
            List<Map<String, Object>> sourceOptions = jdbc.queryForList(
                    "SELECT * FROM exam_question_options WHERE exam_question_id = ? ORDER BY position",
                    sq.get("id"));
            List<Map<String, Object>> targetOptions = jdbc.queryForList(
                    "SELECT * FROM exam_question_options WHERE exam_question_id = ? ORDER BY position",
                    tq.get("id"));
            List<Map<String, Object>> clonedOptions = jdbc.queryForList(
                    "SELECT * FROM question_options WHERE question_version_id = ? ORDER BY position",
                    clonedVersion.get("id"));
            assertThat(targetOptions).hasSameSizeAs(sourceOptions);
            assertThat(clonedOptions).hasSameSizeAs(sourceOptions);
            for (int j = 0; j < sourceOptions.size(); j++) {
                assertThat(targetOptions.get(j).get("option_key"))
                        .isEqualTo(sourceOptions.get(j).get("option_key"));
                assertThat(targetOptions.get(j).get("content"))
                        .as("option content of %s", sq.get("question_type"))
                        .isEqualTo(sourceOptions.get(j).get("content"));
                assertThat(targetOptions.get(j).get("is_correct"))
                        .as("is_correct of %s %s", sq.get("question_type"), sourceOptions.get(j).get("option_key"))
                        .isEqualTo(sourceOptions.get(j).get("is_correct"));
                assertThat(targetOptions.get(j).get("position"))
                        .isEqualTo(sourceOptions.get(j).get("position"));
                assertThat(clonedOptions.get(j).get("option_key"))
                        .isEqualTo(sourceOptions.get(j).get("option_key"));
                assertThat(clonedOptions.get(j).get("content"))
                        .isEqualTo(sourceOptions.get(j).get("content"));
                assertThat(clonedOptions.get(j).get("is_correct"))
                        .isEqualTo(sourceOptions.get(j).get("is_correct"));
                assertThat(clonedOptions.get(j).get("position"))
                        .isEqualTo(sourceOptions.get(j).get("position"));
            }
        }

        // --- ledger row: hashed key (never plaintext), snapshots.
        Map<String, Object> record = jdbc.queryForMap(
                "SELECT * FROM community_fork_records WHERE actor_user_id = ? AND target_exam_id = ?",
                forkerId, targetExamId);
        assertThat((String) record.get("idempotency_key_hash"))
                .matches("^[0-9a-f]{64}$")
                .doesNotContain("happy-key-0001");
        assertThat(record.get("source_publication_id")).isEqualTo(publicationId);
        assertThat(record.get("source_exam_id")).isEqualTo(sourceExamId);
        assertThat(record.get("source_title_snapshot")).isEqualTo("Fork fixture publication");
        assertThat(record.get("source_alias_snapshot")).isEqualTo("Fk-Alias");
        assertThat(record.get("created_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_fork_records WHERE actor_user_id = ?",
                Integer.class, forkerId)).isEqualTo(1);
    }

    // ==================================================================
    // Target is a first-class draft: edit + PUBLISH via existing APIs
    // ==================================================================

    @Test
    void forkerEditsThenPublishesTheTargetThroughExistingExamApis() throws Exception {
        long targetExamId = forkViaApi(publicationId, forkerId, "publish-key-01");

        // Metadata edit through the existing PUT /api/exams/{id}.
        mockMvc.perform(put("/api/exams/" + targetExamId)
                        .with(jwtSubject(forkerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"title\":\"My adapted fork\","
                                + "\"description\":\"edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My adapted fork"));

        // Publish through the existing POST /api/exams/{id}/publish.
        mockMvc.perform(post("/api/exams/" + targetExamId + "/publish")
                        .with(jwtSubject(forkerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.totalPoints").value(13.0))
                .andExpect(jsonPath("$.questionCount").value(4));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, total_points, published_at FROM exams WHERE id = ?", targetExamId);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) row.get("total_points")).doubleValue()).isEqualTo(13.0);
        assertThat(row.get("published_at")).isNotNull();
        // Publish refreshed the composition from the pinned CLONED
        // versions — all four questions survive with their options.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exam_questions WHERE exam_id = ?", Integer.class, targetExamId))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exam_question_options o
                JOIN exam_questions q ON q.id = o.exam_question_id
                WHERE q.exam_id = ?
                """, Integer.class, targetExamId)).isEqualTo(12);
    }

    // ==================================================================
    // Source immutability: composition snapshot, not live versions
    // ==================================================================

    @Test
    void mutatingSourceQuestionVersionAfterPublishDoesNotChangeNewForks() throws Exception {
        long single = sourceQuestionIds.get("SINGLE_CHOICE");
        long numeric = sourceQuestionIds.get("NUMERIC_FILL");

        // First fork BEFORE any mutation — captures the original state.
        long firstTarget = forkViaApi(publicationId, forkerId, "snap-key-0001");

        // Mutate the source bank's latest versions IN PLACE through the
        // existing question edit API (QuestionService mutates the latest
        // version in place).
        questionService.editQuestion(publisherId, single, new UpdateQuestionRequest(
                "MUTATED single choice content",
                com.quizopia.backend.question.domain.model.QuestionDifficulty.HARD,
                "mutated explanation",
                List.of(new OptionPart("A", "mutated A", true),
                        new OptionPart("B", "mutated B", false),
                        new OptionPart("C", "mutated C", false),
                        new OptionPart("D", "mutated D", false)),
                null));
        questionService.editQuestion(publisherId, numeric, new UpdateQuestionRequest(
                "MUTATED numeric content",
                com.quizopia.backend.question.domain.model.QuestionDifficulty.HARD,
                null, null, "9999"));

        // The source exam's published composition never changed.
        assertThat(jdbc.queryForObject(
                "SELECT content FROM exam_questions WHERE exam_id = ? AND question_type = 'SINGLE_CHOICE'",
                String.class, sourceExamId)).isEqualTo("Single choice: pick A");

        // Second fork: cloned from the immutable SNAPSHOT, so it carries
        // the ORIGINAL content and answers, not the mutated versions.
        long secondTarget = forkViaApi(publicationId, forkerId, "snap-key-0002");
        assertThat(secondTarget).isNotEqualTo(firstTarget);

        String clonedSingleContent = jdbc.queryForObject("""
                SELECT eq.content FROM exam_questions eq
                JOIN questions q ON q.id = eq.source_question_id
                WHERE eq.exam_id = ? AND eq.question_type = 'SINGLE_CHOICE'
                """, String.class, secondTarget);
        assertThat(clonedSingleContent)
                .isEqualTo("Single choice: pick A")
                .isNotEqualTo("MUTATED single choice content");

        String clonedVersionContent = jdbc.queryForObject("""
                SELECT v.content FROM question_versions v
                JOIN exam_questions eq ON eq.source_question_version_id = v.id
                WHERE eq.exam_id = ? AND eq.question_type = 'SINGLE_CHOICE'
                """, String.class, secondTarget);
        assertThat(clonedVersionContent).isEqualTo("Single choice: pick A");

        String clonedNumericAnswer = jdbc.queryForObject("""
                SELECT v.answer_key->>'expectedAnswer' FROM question_versions v
                JOIN exam_questions eq ON eq.source_question_version_id = v.id
                WHERE eq.exam_id = ? AND eq.question_type = 'NUMERIC_FILL'
                """, String.class, secondTarget);
        assertThat(clonedNumericAnswer)
                .as("NUMERIC_FILL answer copied from the snapshot, not the mutated bank")
                .isEqualTo("3.14")
                .isNotEqualTo("9999");

        // The first fork is untouched by the mutation as well.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exam_questions WHERE exam_id = ? AND content LIKE 'MUTATED%'",
                Integer.class, firstTarget)).isZero();
    }

    // ==================================================================
    // No copying: zero sessions/attempts/grades; source untouched
    // ==================================================================

    @Test
    void forkCopiesNoSessionsAttemptsGradesAndNeverWritesTheSource() throws Exception {
        Map<String, Object> sourceBefore = jdbc.queryForMap(
                "SELECT version, status, total_points, composition_revision FROM exams WHERE id = ?",
                sourceExamId);
        int sourceQuestionCount = jdbc.queryForObject(
                "SELECT count(*) FROM exam_questions WHERE exam_id = ?", Integer.class, sourceExamId);
        int sourceOptionCount = jdbc.queryForObject("""
                SELECT count(*) FROM exam_question_options o
                JOIN exam_questions q ON q.id = o.exam_question_id
                WHERE q.exam_id = ?
                """, Integer.class, sourceExamId);

        long targetExamId = forkViaApi(publicationId, forkerId, "nocopy-key-01");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exam_sessions WHERE exam_id = ?", Integer.class, targetExamId))
                .as("no sessions copied").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM attempts a JOIN exam_sessions s ON s.id = a.exam_session_id
                WHERE s.exam_id = ?
                """, Integer.class, targetExamId))
                .as("no attempts copied").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM grades g
                JOIN attempts a ON a.id = g.attempt_id
                JOIN exam_sessions s ON s.id = a.exam_session_id
                WHERE s.exam_id = ?
                """, Integer.class, targetExamId))
                .as("no grades copied").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exam_session_participants p
                JOIN exam_sessions s ON s.id = p.exam_session_id
                WHERE s.exam_id = ?
                """, Integer.class, targetExamId))
                .as("no roster rows copied").isZero();

        // Source rows are never written: same optimistic version, same
        // status, same composition shape, bank untouched.
        Map<String, Object> sourceAfter = jdbc.queryForMap(
                "SELECT version, status, total_points, composition_revision FROM exams WHERE id = ?",
                sourceExamId);
        assertThat(sourceAfter).isEqualTo(sourceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exam_questions WHERE exam_id = ?", Integer.class, sourceExamId))
                .isEqualTo(sourceQuestionCount);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM exam_question_options o
                JOIN exam_questions q ON q.id = o.exam_question_id
                WHERE q.exam_id = ?
                """, Integer.class, sourceExamId)).isEqualTo(sourceOptionCount);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM question_banks WHERE id = ?", Integer.class, sourceBankId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM questions WHERE question_bank_id = ?", Integer.class, sourceBankId))
                .isEqualTo(4);
    }

    // ==================================================================
    // Sequential idempotency: 200 replay / 409 conflict
    // ==================================================================

    @Test
    void sameKeySamePublicationReplays200DifferentPublicationConflicts409() throws Exception {
        MvcResult first = mockMvc.perform(fork(publicationId, forkerId, "idem-key-0001"))
                .andExpect(status().isCreated())
                .andReturn();
        long targetExamId = readTree(first).get("targetExamId").asLong();
        String forkedAt = readTree(first).get("forkedAt").asString();

        // Same actor + key + publication → 200, identical target.
        MvcResult replay = mockMvc.perform(fork(publicationId, forkerId, "idem-key-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(readTree(replay).get("targetExamId").asLong()).isEqualTo(targetExamId);
        assertThat(readTree(replay).get("forkedAt").asString()).isEqualTo(forkedAt);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_fork_records WHERE actor_user_id = ?",
                Integer.class, forkerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exams WHERE created_by_user_id = ?", Integer.class, forkerId))
                .as("the replay created nothing")
                .isEqualTo(1);

        // Same actor + key + DIFFERENT publication → 409.
        long otherPublication = secondPublishedPublication();
        mockMvc.perform(fork(otherPublication, forkerId, "idem-key-0001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_CONFLICT"));
    }

    // ==================================================================
    // §7 denied matrix
    // ==================================================================

    @Test
    void guestStudentRolelessAndUnderprivilegedTeachersAreDenied() throws Exception {
        // Guest (no token) → 401 at the chain.
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/fork"))
                .andExpect(status().isUnauthorized());

        // STUDENT holds COMMUNITY_BOOKMARK/REPORT but not the fork gate.
        long student = newUser("fks", "STUDENT");
        mockMvc.perform(fork(publicationId, student, "denied-key-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));

        // Authenticated, no roles at all → 403.
        long roleless = insertUser("fkn");
        mockMvc.perform(fork(publicationId, roleless, "denied-key-02"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));

        // TEACHER without COMMUNITY_FORK (grant removed surgically).
        deleteUserGrant("COMMUNITY_FORK");
        mockMvc.perform(fork(publicationId, forkerId, "denied-key-03"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        restoreUserGrant("COMMUNITY_FORK");

        // TEACHER without EXAM_CREATE (COMMUNITY_FORK still held).
        deleteUserGrant("EXAM_CREATE");
        mockMvc.perform(fork(publicationId, forkerId, "denied-key-04"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
        restoreUserGrant("EXAM_CREATE");

        // No fork record, no target exam was ever created by the denials.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_fork_records", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM exams WHERE created_by_user_id = ?",
                Integer.class, forkerId)).isZero();
    }

    @Test
    void noForkPolicyInvisibleAndAbsentPublicationsAre403Or404() throws Exception {
        jdbc.update("UPDATE community_publications SET reuse_policy = 'NO_FORK' WHERE id = ?",
                publicationId);
        mockMvc.perform(fork(publicationId, forkerId, "nofork-key-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_FORK_NOT_ALLOWED"));
        jdbc.update("UPDATE community_publications SET reuse_policy = 'ALLOW_FORK' WHERE id = ?",
                publicationId);

        jdbc.update("UPDATE community_publications SET moderation_state = 'HIDDEN' WHERE id = ?",
                publicationId);
        mockMvc.perform(fork(publicationId, forkerId, "hidden-key-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));
        jdbc.update("UPDATE community_publications SET moderation_state = 'VISIBLE' WHERE id = ?",
                publicationId);

        jdbc.update("UPDATE community_publications SET status = 'UNPUBLISHED' WHERE id = ?",
                publicationId);
        mockMvc.perform(fork(publicationId, forkerId, "unpub-key-01"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE community_publications SET status = 'PUBLISHED' WHERE id = ?",
                publicationId);

        mockMvc.perform(fork(9_999_999L, forkerId, "absent-key-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMUNITY_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM community_fork_records", Integer.class)).isZero();
    }

    @Test
    void disabledCallerIsDeniedAsDefenseInDepth() throws Exception {
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", forkerId);
        // With a forged (structurally valid) JWT the AuthN layer normally
        // rejects disabled accounts; the service-layer ACTIVE check is
        // the defense-in-depth 403 here.
        mockMvc.perform(fork(publicationId, forkerId, "disabled-k1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_DENIED"));
    }

    // ==================================================================
    // Idempotency-Key validation (§7/§8 step 1)
    // ==================================================================

    @Test
    void idempotencyKeyValidationRejectsMissingShortLongAndNonPrintable() throws Exception {
        // Missing header.
        mockMvc.perform(post("/api/community/publications/" + publicationId + "/fork")
                        .with(jwtSubject(forkerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_KEY_INVALID"));
        // 7 chars (too short).
        mockMvc.perform(fork(publicationId, forkerId, "short7!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_KEY_INVALID"));
        // 129 chars (too long).
        mockMvc.perform(fork(publicationId, forkerId, "x".repeat(129)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_KEY_INVALID"));
        // Non-printable ASCII (tab) inside an otherwise valid key.
        mockMvc.perform(fork(publicationId, forkerId, "bad\tkey\tvalue!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_KEY_INVALID"));
        // Non-ASCII (Unicode) character.
        mockMvc.perform(fork(publicationId, forkerId, "ключ-форка-0001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUNITY_IDEMPOTENCY_KEY_INVALID"));

        // Boundary keys ARE valid: exactly 8 and exactly 128 printable.
        mockMvc.perform(fork(publicationId, forkerId, "exact-8k"))
                .andExpect(status().isCreated());
        long otherPublication = secondPublishedPublication();
        mockMvc.perform(fork(otherPublication, forkerId, "y".repeat(128)))
                .andExpect(status().isCreated());

        // §9: only syntactically VALID requests ever reach admission —
        // the five invalid ones above consumed no FORK bucket attempts
        // (the two valid ones consumed exactly 2).
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM community_rate_buckets WHERE bucket_key = ?",
                Integer.class, rateLimiter.userBucketKey(CommunityRateLimiter.Policy.FORK, forkerId));
        assertThat(attempts).isEqualTo(2);
    }

    // ==================================================================
    // fixtures
    // ==================================================================

    private static RequestBuilder fork(long publicationId, long userId, String idempotencyKey) {
        return post("/api/community/publications/" + publicationId + "/fork")
                .with(jwtSubject(userId))
                .header("Idempotency-Key", idempotencyKey);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtSubject(
            long userId) {
        return jwt().jwt(j -> j.subject(String.valueOf(userId)));
    }

    private long forkViaApi(long pubId, long userId, String key) throws Exception {
        MvcResult result = mockMvc.perform(fork(pubId, userId, key))
                .andExpect(status().isCreated())
                .andReturn();
        return readTree(result).get("targetExamId").asLong();
    }

    /**
     * Source bank + one question of each type with version 1 + options,
     * all owned by the publisher. Contents are distinctive so that
     * snapshot-vs-live mutation checks are unambiguous.
     */
    private long insertSourceContent() {
        long bankId = insert("""
                INSERT INTO question_banks (created_by_user_id, code, name, status, visibility)
                VALUES (%d, 'FKB%s', 'Source bank', 'ACTIVE', 'PRIVATE')
                """.formatted(publisherId, seq++));

        sourceQuestionIds = new java.util.HashMap<>();
        // SINGLE_CHOICE — A correct.
        long single = insertQuestion(bankId, "FQS" + seq++, "SINGLE_CHOICE",
                "Single choice: pick A", "EASY", 2.5, null,
                List.of("A|Alpha is right|t", "B|Beta is wrong|f", "C|Gamma is wrong|f",
                        "D|Delta is wrong|f"));
        sourceQuestionIds.put("SINGLE_CHOICE", single);
        // MULTIPLE_CHOICE — A and C correct.
        long multiple = insertQuestion(bankId, "FQM" + seq++, "MULTIPLE_CHOICE",
                "Multiple: pick A and C", "MEDIUM", 3.5, "Pick two",
                List.of("A|Alpha counts|t", "B|Beta does not|f", "C|Gamma counts|t",
                        "D|Delta does not|f"));
        sourceQuestionIds.put("MULTIPLE_CHOICE", multiple);
        // TRUE_FALSE_MATRIX — 4 statements, mixed truth values.
        long matrix = insertQuestion(bankId, "FQT" + seq++, "TRUE_FALSE_MATRIX",
                "TF matrix: mark each statement", "HARD", 4, "All four statements count",
                List.of("A|First is true|t", "B|Second is false|f", "C|Third is true|t",
                        "D|Fourth is false|f"));
        sourceQuestionIds.put("TRUE_FALSE_MATRIX", matrix);
        // NUMERIC_FILL — answer_key {"expectedAnswer":"3.14"}.
        long numeric = insertQuestion(bankId, "FQN" + seq++, "NUMERIC_FILL",
                "Numeric: value of pi (2dp)", "MEDIUM", 3, null, null);
        // answer_key rides in the version INSERT (see insertQuestion).
        sourceQuestionIds.put("NUMERIC_FILL", numeric);
        return bankId;
    }

    private long insertQuestion(long bankId, String code, String type, String content,
            String difficulty, double points, String explanation, List<String> options) {
        // NUMERIC_FILL versions must carry answer_key in the same INSERT
        // (chk_question_versions_answer_key_presence: present iff the type).
        String answerKey = "NUMERIC_FILL".equals(type)
                ? "'{\"expectedAnswer\":\"3.14\"}'::jsonb" : "NULL";
        String metadata = "NUMERIC_FILL".equals(type)
                ? "'{\"source\":\"fixture\",\"row\":7}'::jsonb" : "'{\"origin\":\"source\"}'::jsonb";
        long questionId = insert("""
                INSERT INTO questions (question_bank_id, code, status, created_by)
                VALUES (%d, '%s', 'ACTIVE', %d)
                """.formatted(bankId, code, publisherId));
        long versionId = insert("""
                INSERT INTO question_versions (question_id, version_number, question_type, content,
                                               explanation, difficulty, default_points, answer_key,
                                               metadata, created_by)
                VALUES (%d, 1, '%s', '%s', %s, '%s', %s, %s, %s, %d)
                """.formatted(questionId, type, content,
                        explanation == null ? "NULL" : "'" + explanation + "'",
                        difficulty, points, answerKey, metadata, publisherId));
        if (options != null) {
            for (int i = 0; i < options.size(); i++) {
                String[] parts = options.get(i).split("\\|");
                jdbc.update("""
                        INSERT INTO question_options (question_version_id, option_key, content,
                                                      is_correct, position)
                        VALUES (?, ?, ?, ?, ?)
                        """, versionId, parts[0], parts[1], "t".equals(parts[2]), i);
            }
        }
        return questionId;
    }

    /** Published source exam: 2 sections × 2 questions, custom ladder. */
    private long insertSourceExam() {
        long subjectId = jdbc.queryForObject(
                "SELECT id FROM subjects WHERE status = 'ACTIVE' ORDER BY id LIMIT 1", Long.class);
        Long purposeId = jdbc.queryForObject(
                "SELECT id FROM exam_purposes ORDER BY id LIMIT 1", Long.class);
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, subject_id, purpose_id, code, title, description,
                                   instructions, duration_minutes, total_points, tf_matrix_scoring,
                                   status, published_at)
                VALUES (%d, %d, %s, 'FKEX%s', 'Fork fixture exam', 'Source description',
                        'Read carefully', 45, 13,
                        '{"0":0,"1":5,"2":15,"3":40,"4":90}'::jsonb, 'PUBLISHED', now())
                """.formatted(publisherId, subjectId,
                        purposeId == null ? "NULL" : purposeId, seq++));

        long section1 = insert("""
                INSERT INTO exam_sections (exam_id, code, title, instructions, position)
                VALUES (%d, 'S1', 'Part one', 'Section one instructions', 0)
                """.formatted(examId));
        long section2 = insert("""
                INSERT INTO exam_sections (exam_id, code, title, instructions, position)
                VALUES (%d, 'S2', 'Part two', NULL, 1)
                """.formatted(examId));

        insertExamQuestion(examId, section1, "SINGLE_CHOICE", 0, 2.5,
                "Single choice: pick A", "EASY", "A is correct", null);
        insertExamQuestion(examId, section1, "NUMERIC_FILL", 1, 3,
                "Numeric: value of pi (2dp)", "MEDIUM", "two decimals", "3.14");
        insertExamQuestion(examId, section2, "MULTIPLE_CHOICE", 0, 3.5,
                "Multiple: pick A and C", "MEDIUM", "Pick two", null);
        insertExamQuestion(examId, section2, "TRUE_FALSE_MATRIX", 1, 4,
                "TF matrix: mark each statement", "HARD", null, null);
        return examId;
    }

    /** exam_questions row snapshotting the pinned source version + options. */
    private void insertExamQuestion(long examId, long sectionId, String type, int position,
            double points, String content, String difficulty, String explanation, String answer) {
        long sourceQuestionId = sourceQuestionIds.get(type);
        long sourceVersionId = jdbc.queryForObject(
                "SELECT id FROM question_versions WHERE question_id = ? AND version_number = 1",
                Long.class, sourceQuestionId);
        String questionCode = jdbc.queryForObject(
                "SELECT code FROM questions WHERE id = ?", String.class, sourceQuestionId);
        long eqId = insert("""
                INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id,
                                            source_question_version_id, question_code, question_type,
                                            content, default_points, difficulty, explanation,
                                            answer_key, metadata, position)
                VALUES (%d, %d, %d, %d, '%s', '%s', '%s', %s, '%s', %s, %s,
                        '{"origin":"exam-snapshot"}'::jsonb, %d)
                """.formatted(examId, sectionId, sourceQuestionId, sourceVersionId, questionCode,
                        type, content, points, difficulty,
                        explanation == null ? "NULL" : "'" + explanation + "'",
                        answer == null ? "NULL" : "'{\"expectedAnswer\":\"" + answer + "\"}'::jsonb",
                        position));
        List<Map<String, Object>> sourceOptions = jdbc.queryForList(
                "SELECT * FROM question_options WHERE question_version_id = ? ORDER BY position",
                sourceVersionId);
        for (Map<String, Object> o : sourceOptions) {
            jdbc.update("""
                    INSERT INTO exam_question_options (exam_question_id, option_key, content,
                                                       is_correct, position)
                    VALUES (?, ?, ?, ?, ?)
                    """, eqId, o.get("option_key"), o.get("content"), o.get("is_correct"),
                    o.get("position"));
        }
    }

    /** A second PUBLISHED+VISIBLE ALLOW_FORK publication (same publisher). */
    private long secondPublishedPublication() {
        long examId = insert("""
                INSERT INTO exams (created_by_user_id, code, title, status, published_at, total_points)
                VALUES (%d, 'FKE2%s', 'Second source exam', 'PUBLISHED', now(), 1)
                """.formatted(publisherId, seq++));
        return insert("""
                INSERT INTO community_publications (exam_id, publisher_user_id, title, author_alias,
                                                    status, moderation_state, reuse_policy,
                                                    published_at)
                VALUES (%d, %d, 'Second publication', 'Fk2-Alias', 'PUBLISHED', 'VISIBLE',
                        'ALLOW_FORK', now())
                """.formatted(examId, publisherId));
    }

    /** Removes a TEACHER permission grant for this (rolled-back) test only. */
    private void deleteUserGrant(String permissionCode) {
        jdbc.update("""
                DELETE FROM role_permissions
                WHERE role_id = (SELECT id FROM roles WHERE code = 'TEACHER')
                  AND permission_id = (SELECT id FROM permissions WHERE code = ?)
                """, permissionCode);
    }

    private void restoreUserGrant(String permissionCode) {
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM roles r, permissions p
                WHERE r.code = 'TEACHER' AND p.code = ?
                """, permissionCode);
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
                VALUES ('%s%s', '%s%s@t.example.com', 'h', 'FK', 'ACTIVE', now(), 0, 0, now())
                """.formatted(prefix, seq, prefix, seq++));
    }

    private static String jsonText(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString().replaceAll("\\s+", "");
    }

    private static JsonNode readTree(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> keySet(JsonNode object) {
        Set<String> keys = new LinkedHashSet<>();
        object.properties().forEach(p -> keys.add(p.getKey()));
        return keys;
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
