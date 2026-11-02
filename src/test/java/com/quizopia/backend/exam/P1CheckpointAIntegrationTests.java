package com.quizopia.backend.exam;

import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.domain.model.Exam;
import com.quizopia.backend.exam.domain.model.ExamQuestion;
import com.quizopia.backend.exam.domain.model.ExamSection;
import com.quizopia.backend.exam.domain.model.ExamStatus;
import com.quizopia.backend.exam.repository.ExamQuestionOptionRepository;
import com.quizopia.backend.exam.repository.ExamQuestionRepository;
import com.quizopia.backend.exam.repository.ExamRepository;
import com.quizopia.backend.exam.repository.ExamSectionRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Checkpoint A: executable regressions only; permissions and workspace policy use the real DB. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PostgresTestContainerConfiguration.class)
class P1CheckpointAIntegrationTests {

    private static final long CREATOR = 8_100_001L;
    private static final long OTHER_CREATOR = 8_100_002L;
    private static final long OWNER = 8_100_003L;
    private static final long ADMIN = 8_100_004L;
    private static final long MEMBER = 8_100_005L;
    private static final long OUTSIDER = 8_100_006L;
    private static final long WORKSPACE = 8_100_010L;
    private static final long SOURCE_EXAM = 8_100_020L;
    private static final long OTHER_EXAM = 8_100_021L;
    private static final long SECTION = 8_100_030L;
    private static final long TF_QUESTION = 8_100_040L;
    private static final long NUMERIC_QUESTION = 8_100_041L;
    private static final long ALPHA = 8_100_101L;
    private static final long BETA = 8_100_102L;
    private static final long GAMMA = 8_100_103L;
    private static final long DELTA = 8_100_104L;
    private static final long DECOY = 8_100_105L;
    private static final String SCORING = "{\"0\":0,\"1\":5,\"2\":20,\"3\":60,\"4\":100}";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ExamService examService;
    @Autowired private ExamRepository exams;
    @Autowired private ExamSectionRepository sections;
    @Autowired private ExamQuestionRepository questions;
    @Autowired private ExamQuestionOptionRepository options;
    @Autowired private ExamSessionRepository sessions;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void fixture() {
        // Explicit ownership keys avoid school/profile provisioning and legacy insert defaults.
        for (long user : List.of(CREATOR, OTHER_CREATOR, OWNER, ADMIN, MEMBER, OUTSIDER)) {
            jdbc.update("INSERT INTO users (id, username, email, password_hash, display_name) "
                    + "VALUES (?, ?, ?, 'unused-test-hash', ?)",
                    user, "p1a-" + user, "p1a-" + user + "@test.com", "P1 A " + user);
        }
        jdbc.update("INSERT INTO organizations (id, code, name) OVERRIDING SYSTEM VALUE "
                + "VALUES (8100010, 'P1A-ORG', 'P1 A organization')");
        jdbc.update("INSERT INTO workspaces (id, workspace_type, name, organization_id) "
                + "OVERRIDING SYSTEM VALUE VALUES (?, 'ORGANIZATION', 'P1 A organization', 8100010)",
                WORKSPACE);
        membership(8_100_201L, CREATOR, "EDITOR");
        membership(8_100_202L, OTHER_CREATOR, "EDITOR");
        membership(8_100_203L, OWNER, "OWNER");
        membership(8_100_204L, ADMIN, "ADMIN");
        membership(8_100_205L, MEMBER, "MEMBER");
        // An outsider still has an accessible workspace: denial cannot rely on an empty scope.
        jdbc.update("INSERT INTO workspaces (id, workspace_type, name, owner_user_id) "
                + "OVERRIDING SYSTEM VALUE VALUES (8100011, 'PERSONAL', 'Outsider personal', ?)", OUTSIDER);
        jdbc.update("INSERT INTO workspace_memberships (id, workspace_id, user_id, role, status) "
                + "OVERRIDING SYSTEM VALUE VALUES (8100206, 8100011, ?, 'OWNER', 'ACTIVE')", OUTSIDER);

        for (long exam : List.of(SOURCE_EXAM, OTHER_EXAM)) {
            jdbc.update("INSERT INTO exams (id, workspace_id, created_by_user_id, code, title, description, "
                    + "instructions, duration_minutes, status, published_at, total_points) "
                    + "VALUES (?, ?, ?, ?, 'P1 A published content', 'Original description', "
                    + "'Read every statement', 45, 'PUBLISHED', TIMESTAMPTZ '2030-01-01 00:00:00Z', 7.50)",
                    exam, WORKSPACE, CREATOR, "P1A-EX-" + exam);
        }
        // The current exams column accepts this valid monotone, nondefault ladder.
        // The detached exam_versions table has a fixed-object CHECK and must not be used.
        jdbc.update("UPDATE exams SET tf_matrix_scoring = CAST(? AS jsonb) WHERE id = ?", SCORING, SOURCE_EXAM);

        jdbc.update("INSERT INTO question_banks (id, workspace_id, created_by_user_id, code, name) "
                + "VALUES (8100050, ?, ?, 'P1A-BANK', 'P1 A bank')", WORKSPACE, CREATOR);
        for (int position = 0; position < 2; position++) {
            long sourceQuestion = 8_100_060L + position;
            long version = 8_100_070L + position;
            String type = position == 0 ? "TRUE_FALSE_MATRIX" : "NUMERIC_FILL";
            String answer = position == 0 ? null : "{\"expectedAnswer\":\"1.25\"}";
            BigDecimal points = new BigDecimal(position == 0 ? "2.50" : "5.00");
            jdbc.update("INSERT INTO questions (id, question_bank_id, code, status, created_by) "
                    + "VALUES (?, 8100050, ?, 'ACTIVE', ?)", sourceQuestion, "P1A-Q" + position, CREATOR);
            jdbc.update("INSERT INTO question_versions (id, question_id, version_number, question_type, "
                    + "content, explanation, difficulty, default_points, answer_key, metadata, created_by) "
                    + "VALUES (?, ?, 1, ?, ?, 'Pinned explanation', 'HARD', ?, CAST(? AS jsonb), "
                    + "'{\"nested\":{\"label\":\"original\"}}'::jsonb, ?)",
                    version, sourceQuestion, type, "Pinned content " + position, points, answer, CREATOR);
        }
        jdbc.update("INSERT INTO exam_sections (id, exam_id, code, title, instructions, position) "
                + "VALUES (?, ?, 'P1A-SECTION', 'Original section', 'Section instructions', 0)", SECTION, SOURCE_EXAM);
        jdbc.update("INSERT INTO exam_questions (id, exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, explanation, "
                + "difficulty, default_points, answer_key, metadata, position) "
                + "SELECT ?, ?, ?, q.id, v.id, q.code, v.question_type, v.content, v.explanation, "
                + "v.difficulty, v.default_points, v.answer_key, v.metadata, 0 "
                + "FROM questions q JOIN question_versions v ON v.question_id = q.id WHERE q.id = 8100060",
                TF_QUESTION, SOURCE_EXAM, SECTION);
        jdbc.update("INSERT INTO exam_questions (id, exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, explanation, "
                + "difficulty, default_points, answer_key, metadata, position) "
                + "SELECT ?, ?, ?, q.id, v.id, q.code, v.question_type, v.content, v.explanation, "
                + "v.difficulty, v.default_points, v.answer_key, v.metadata, 1 "
                + "FROM questions q JOIN question_versions v ON v.question_id = q.id WHERE q.id = 8100061",
                NUMERIC_QUESTION, SOURCE_EXAM, SECTION);
        for (int position = 0; position < 4; position++) {
            String key = String.valueOf((char) ('A' + position));
            jdbc.update("INSERT INTO question_options (id, question_version_id, option_key, content, is_correct, position) "
                    + "VALUES (?, 8100070, ?, ?, ?, ?)", 8_100_080L + position, key,
                    "Statement " + key, position % 2 == 0, position);
            jdbc.update("INSERT INTO exam_question_options (id, exam_question_id, option_key, content, is_correct, position) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", 8_100_090L + position, TF_QUESTION, key,
                    "Statement " + key, position % 2 == 0, position);
        }

        session(ALPHA, SOURCE_EXAM, CREATOR, "P1A-ALPHA", "Alpha shared", "DRAFT");
        session(BETA, SOURCE_EXAM, OTHER_CREATOR, "P1A-BETA-CODE", "Beta shared", "SCHEDULED");
        session(GAMMA, SOURCE_EXAM, OWNER, "P1A-GAMMA", "Gamma shared", "DRAFT");
        session(DELTA, SOURCE_EXAM, ADMIN, "P1A-DELTA", "Delta closed", "CLOSED");
        session(DECOY, OTHER_EXAM, OWNER, "P1A-DECOY", "Aardvark shared", "DRAFT");
        jdbc.update("INSERT INTO attempts (id, exam_session_id, user_id, attempt_number, status, "
                + "started_at, deadline_at, submitted_at, submission_idempotency_key) "
                + "VALUES (8100110, ?, ?, 1, 'SUBMITTED', TIMESTAMPTZ '2030-01-01 01:00:00Z', "
                + "TIMESTAMPTZ '2030-01-01 01:45:00Z', TIMESTAMPTZ '2030-01-01 01:20:00Z', 'p1a-submit')",
                DELTA, MEMBER);
    }

    @Test
    void duplicatePreservesNondefaultScoringWithIndependentEntityJson() {
        grant(CREATOR, "TEACHER");
        var before = sourceSnapshot();
        Exam source = exams.findById(SOURCE_EXAM).orElseThrow();
        JsonNode expected = mapper.readTree(SCORING);
        assertThat(source.getTfMatrixScoring()).isEqualTo(expected);

        long copyId = examService.duplicateExam(CREATOR, SOURCE_EXAM).id();
        // Compare managed entity references before clear/reload; DTOs deep-copy JSON themselves.
        Exam copy = em.find(Exam.class, copyId);
        assertThat(em.contains(source)).isTrue();
        assertThat(em.contains(copy)).isTrue();
        assertAll(
                () -> assertThat(copy.getTfMatrixScoring()).as("duplicate grading policy").isEqualTo(expected),
                () -> assertThat(copy.getTfMatrixScoring()).isNotSameAs(source.getTfMatrixScoring()),
                () -> assertThat(source.getTfMatrixScoring()).isEqualTo(expected),
                () -> assertThat(sourceSnapshot()).isEqualTo(before));

        ((ObjectNode) copy.getTfMatrixScoring()).put("1", 6);
        assertThat(source.getTfMatrixScoring()).isEqualTo(expected);
        em.flush();
        em.clear();
        assertThat(exams.findById(copyId).orElseThrow().getTfMatrixScoring().path("1").asInt()).isEqualTo(6);
        assertThat(exams.findById(SOURCE_EXAM).orElseThrow().getTfMatrixScoring()).isEqualTo(expected);
        assertThat(sourceSnapshot()).isEqualTo(before);
    }

    @Test
    void duplicateDeepCopiesPublishedContentWithoutSessionsOrAttemptsAndLeavesSourceUntouched() {
        grant(CREATOR, "TEACHER");
        var before = sourceSnapshot();
        Exam source = exams.findById(SOURCE_EXAM).orElseThrow();
        ExamSection sourceSection = sections.findAllByExamIdOrderByPositionAsc(SOURCE_EXAM).getFirst();
        List<ExamQuestion> sourceQuestions = questions.findAllByExamSectionIdOrderByPositionAsc(SECTION);

        long copyId = examService.duplicateExam(CREATOR, SOURCE_EXAM).id();
        Exam copy = exams.findById(copyId).orElseThrow();
        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(copy.getCode()).isNotEqualTo(source.getCode());
        assertThat(copy.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(copy.getPublishedAt()).isNull();
        assertThat(copy.getSourceExamId()).isEqualTo(SOURCE_EXAM);
        assertThat(copy).usingRecursiveComparison()
                .ignoringFields("id", "code", "status", "publishedAt", "sourceExamId", "totalPoints",
                        "tfMatrixScoring", "version", "compositionRevision", "createdAt", "updatedAt")
                .isEqualTo(source);
        assertThat(copy.getTotalPoints()).isEqualByComparingTo(BigDecimal.ZERO);

        List<ExamSection> copiedSections = sections.findAllByExamIdOrderByPositionAsc(copyId);
        assertThat(copiedSections).hasSize(1);
        ExamSection copiedSection = copiedSections.getFirst();
        assertThat(copiedSection.getId()).isNotEqualTo(sourceSection.getId());
        assertThat(copiedSection.getExamId()).isEqualTo(copyId);
        assertThat(copiedSection).usingRecursiveComparison().ignoringFields("id", "examId", "createdAt")
                .isEqualTo(sourceSection);
        List<ExamQuestion> copiedQuestions = questions.findAllByExamSectionIdOrderByPositionAsc(copiedSection.getId());
        assertThat(copiedQuestions).hasSize(2);
        for (int i = 0; i < sourceQuestions.size(); i++) {
            ExamQuestion original = sourceQuestions.get(i);
            ExamQuestion duplicate = copiedQuestions.get(i);
            assertThat(duplicate.getId()).isNotIn(sourceQuestions.stream().map(ExamQuestion::getId).toList());
            assertThat(duplicate.getExamId()).isEqualTo(copyId);
            assertThat(duplicate.getExamSectionId()).isEqualTo(copiedSection.getId());
            assertThat(duplicate).usingRecursiveComparison().ignoringFields("id", "examId", "examSectionId", "createdAt")
                    .isEqualTo(original);
            assertThat(duplicate.getMetadata()).isNotSameAs(original.getMetadata());
            assertThat(duplicate.getMetadata().path("nested")).isNotSameAs(original.getMetadata().path("nested"));
            if (original.getAnswerKey() != null) {
                assertThat(duplicate.getAnswerKey()).isNotSameAs(original.getAnswerKey());
            }
            var originalOptions = options.findAllByExamQuestionIdOrderByPositionAsc(original.getId());
            var copiedOptions = options.findAllByExamQuestionIdOrderByPositionAsc(duplicate.getId());
            assertThat(copiedOptions).hasSize(i == 0 ? 4 : 0);
            for (int j = 0; j < originalOptions.size(); j++) {
                assertThat(copiedOptions.get(j).getId())
                        .isNotIn(originalOptions.stream().map(o -> o.getId()).toList());
                assertThat(copiedOptions.get(j).getExamQuestionId()).isEqualTo(duplicate.getId());
                assertThat(copiedOptions.get(j)).usingRecursiveComparison()
                        .ignoringFields("id", "examQuestionId", "createdAt").isEqualTo(originalOptions.get(j));
            }
        }
        assertThat(sessions.findAllByExamId(copyId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts a JOIN exam_sessions s "
                + "ON s.id = a.exam_session_id WHERE s.exam_id = ?", Long.class, copyId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attempts", Long.class)).isEqualTo(1L);
        assertThat(sourceSnapshot()).isEqualTo(before);

        ((ObjectNode) copiedQuestions.getFirst().getMetadata().path("nested")).put("label", "copy only");
        ((ObjectNode) copiedQuestions.get(1).getAnswerKey()).put("expectedAnswer", "9.99");
        assertThat(sourceQuestions.getFirst().getMetadata().path("nested").path("label").asString()).isEqualTo("original");
        assertThat(sourceQuestions.get(1).getAnswerKey().path("expectedAnswer").asString()).isEqualTo("1.25");
        em.flush();
        em.clear();
        assertThat(questions.findById(copiedQuestions.getFirst().getId()).orElseThrow()
                .getMetadata().path("nested").path("label").asString()).isEqualTo("copy only");
        assertThat(questions.findById(copiedQuestions.get(1).getId()).orElseThrow()
                .getAnswerKey().path("expectedAnswer").asString()).isEqualTo("9.99");
        assertThat(sourceSnapshot()).isEqualTo(before);
    }

    @Test
    void duplicateStartsWithZeroTotalAndRecomputesEditedPointsOnlyAtPublish() {
        grant(CREATOR, "TEACHER");
        var before = sourceSnapshot();
        var duplicate = examService.duplicateExam(CREATOR, SOURCE_EXAM);
        assertThat(duplicate.totalPoints()).isEqualByComparingTo("0");
        assertThat(exams.findById(duplicate.id()).orElseThrow().getTotalPoints()).isEqualByComparingTo("0");
        em.flush();
        jdbc.update("UPDATE exam_questions SET default_points = 3.50 WHERE exam_id = ? AND position = 0", duplicate.id());
        em.clear();
        assertThat(exams.findById(duplicate.id()).orElseThrow().getTotalPoints()).isEqualByComparingTo("0");

        var published = examService.publishExam(CREATOR, duplicate.id(), null);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.totalPoints()).isEqualByComparingTo("8.50");
        em.flush();
        em.clear();
        assertThat(exams.findById(duplicate.id()).orElseThrow().getTotalPoints()).isEqualByComparingTo("8.50");
        assertThat(sourceSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "nested list: active {0} sees sessions from every creator")
    @CsvSource({"OWNER, 8100003", "ADMIN, 8100004"})
    void nestedOwnerOrAdminListsAllParentSessions(String role, long caller) throws Exception {
        grant(caller, "ACADEMIC_ADMIN");
        assertThat(caller).isNotIn(CREATOR, OTHER_CREATOR);
        assertThat(jdbc.queryForObject("SELECT role FROM workspace_memberships WHERE workspace_id = ? AND user_id = ?",
                String.class, WORKSPACE, caller)).isEqualTo(role);
        readExam(caller);
        expectIds(nested(caller).param("sort", "title,asc"), ALPHA, BETA, DELTA, GAMMA)
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].examId").value(SOURCE_EXAM))
                .andExpect(jsonPath("$.items[1].examId").value(SOURCE_EXAM))
                .andExpect(jsonPath("$.items[2].examId").value(SOURCE_EXAM))
                .andExpect(jsonPath("$.items[3].examId").value(SOURCE_EXAM));
    }

    @Test
    void nestedActiveMemberCanListSessionsWhenParentExamReadIsAllowed() throws Exception {
        grant(MEMBER, "TEACHER");
        // ExamService.requireViewableExam and WorkspaceAccessService.requireResourceRead
        // explicitly allow any ACTIVE organization member, including MEMBER.
        readExam(MEMBER);
        expectIds(nested(MEMBER).param("sort", "title,asc"), ALPHA, BETA, DELTA, GAMMA);
    }

    @ParameterizedTest(name = "nested filters: search={0}, status={1}")
    @CsvSource({"'  sHaReD  ', DRAFT, 8100101, 8100103", "'  beta-code  ', SCHEDULED, 8100102, 8100102"})
    void nestedSearchAndStatusFilterAcrossCreators(String search, String state, long first, long last) throws Exception {
        grant(ADMIN, "ACADEMIC_ADMIN");
        long[] expected = first == last ? new long[] {first} : new long[] {first, last};
        expectIds(nested(ADMIN).param("search", search).param("status", state).param("sort", "title,asc"), expected)
                .andExpect(jsonPath("$.totalElements").value(expected.length))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void nestedPaginationAndDescendingSortUseAllParentSessions() throws Exception {
        grant(ADMIN, "ACADEMIC_ADMIN");
        expectIds(nested(ADMIN).param("page", "0").param("size", "2").param("sort", "title,desc"), GAMMA, DELTA)
                .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.sort").value("title: DESC,id: DESC"));
        expectIds(nested(ADMIN).param("page", "1").param("size", "2").param("sort", "title,desc"), BETA, ALPHA)
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void nestedOutsiderIsDeniedRatherThanGivenAnEmptySuccess() throws Exception {
        grant(OUTSIDER, "TEACHER");
        denied(get("/api/exams/{id}", SOURCE_EXAM).with(subject(OUTSIDER)));
        denied(nested(OUTSIDER));
    }

    @ParameterizedTest(name = "nested revoked {0} membership is denied")
    @ValueSource(strings = {"SUSPENDED", "REMOVED"})
    void nestedRevokedAdminIsDeniedRatherThanGivenAnEmptySuccess(String membershipStatus) throws Exception {
        grant(ADMIN, "ACADEMIC_ADMIN");
        revoke(ADMIN, membershipStatus);
        denied(get("/api/exams/{id}", SOURCE_EXAM).with(subject(ADMIN)));
        denied(nested(ADMIN));
    }

    @ParameterizedTest(name = "nested revoked creator ({0}) is denied despite owning content")
    @ValueSource(strings = {"SUSPENDED", "REMOVED"})
    void nestedRevokedCreatorIsDeniedRatherThanGivenAnEmptySuccess(String membershipStatus) throws Exception {
        grant(CREATOR, "TEACHER");
        revoke(CREATOR, membershipStatus);
        denied(get("/api/exams/{id}", SOURCE_EXAM).with(subject(CREATOR)));
        denied(nested(CREATOR));
    }

    @ParameterizedTest(name = "legacy my list stays creator-only for {0}")
    @CsvSource({"OWNER, 8100003, 8100105, 8100103", "ADMIN, 8100004, 8100104, 8100104"})
    void legacyMySessionsRemainsCreatedByOnly(String role, long caller, long first, long last) throws Exception {
        grant(caller, "ACADEMIC_ADMIN");
        long[] expected = first == last ? new long[] {first} : new long[] {first, last};
        expectIds(get("/api/exam-sessions/my").with(subject(caller)).param("sort", "title,asc"), expected)
                .andExpect(jsonPath("$.totalElements").value(expected.length));
        expectIds(get("/api/exam-sessions/my").with(subject(caller))
                .param("examId", String.valueOf(SOURCE_EXAM)), caller == OWNER ? GAMMA : DELTA)
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void nestedRefreshesDueAndExpiredAcrossCreatorsBeforeFilteringAndIsIdempotent() throws Exception {
        grant(ADMIN, "ACADEMIC_ADMIN");
        Instant before = Instant.now().minusSeconds(1);
        jdbc.update("UPDATE exam_sessions SET status='SCHEDULED', starts_at=now()-interval '2 hours', "
                + "ends_at=now()+interval '1 hour' WHERE id=?", ALPHA);
        jdbc.update("UPDATE exam_sessions SET starts_at=now()-interval '2 hours', "
                + "ends_at=now()-interval '1 hour' WHERE id=?", BETA);
        jdbc.update("UPDATE exam_sessions SET status='OPEN', starts_at=now()-interval '2 hours', "
                + "ends_at=now()-interval '1 hour', opened_at=now()-interval '2 hours' WHERE id=?", GAMMA);
        var original = jdbc.queryForList("SELECT * FROM exam_sessions WHERE id IN (?, ?, ?) ORDER BY id",
                GAMMA, DELTA, DECOY);
        expectIds(nested(ADMIN).param("status", "OPEN"), ALPHA);
        expectIds(nested(ADMIN).param("status", "CLOSED").param("sort", "title,asc"), BETA, DELTA, GAMMA);
        var refreshed = jdbc.queryForList("SELECT * FROM exam_sessions ORDER BY id");
        Instant after = Instant.now().plusSeconds(1);
        for (long id : List.of(ALPHA, BETA, GAMMA)) {
            var row = jdbc.queryForMap("SELECT * FROM exam_sessions WHERE id=?", id);
            Instant opened = ((Timestamp) row.get("opened_at")).toInstant();
            if (id != GAMMA) assertThat(opened).isBetween(before, after);
            if (id == ALPHA) assertThat(row.get("closed_at")).isNull();
            else assertThat(((Timestamp) row.get("closed_at")).toInstant()).isBetween(before, after);
            if (id == BETA) assertThat(row.get("closed_at")).isEqualTo(row.get("opened_at"));
            assertThat(row.get("version")).isEqualTo(0);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM exam_sessions WHERE id=?", GAMMA).get("opened_at"))
                .isEqualTo(original.getFirst().get("opened_at"));
        assertThat(jdbc.queryForMap("SELECT * FROM exam_sessions WHERE id=?", DELTA)).isEqualTo(original.get(1));
        assertThat(jdbc.queryForMap("SELECT * FROM exam_sessions WHERE id=?", DECOY)).isEqualTo(original.get(2));
        expectIds(nested(ADMIN).param("status", "OPEN"), ALPHA);
        assertThat(jdbc.queryForList("SELECT * FROM exam_sessions ORDER BY id")).isEqualTo(refreshed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTSIDER", "SUSPENDED", "REMOVED", "NO_PERMISSION"})
    void deniedNestedRefreshHasNoLifecycleSideEffects(String denial) throws Exception {
        long caller = denial.equals("OUTSIDER") ? OUTSIDER : ADMIN;
        if (!denial.equals("NO_PERMISSION")) grant(caller, "ACADEMIC_ADMIN");
        if (denial.equals("SUSPENDED") || denial.equals("REMOVED")) revoke(caller, denial);
        jdbc.update("UPDATE exam_sessions SET starts_at=now()-interval '2 hours', "
                + "ends_at=now()-interval '1 hour' WHERE id=?", BETA);
        var before = sourceSnapshot();
        denied(nested(caller));
        assertThat(sourceSnapshot()).isEqualTo(before);
    }

    private void membership(long id, long user, String role) {
        jdbc.update("INSERT INTO workspace_memberships (id, workspace_id, user_id, role, status) "
                + "OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, 'ACTIVE')", id, WORKSPACE, user, role);
    }

    private void grant(long user, String role) {
        assertThat(jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?",
                user, role)).as("DB-backed role grant").isEqualTo(1);
    }

    private void session(long id, long exam, long creator, String code, String title, String state) {
        // Far-future scheduled windows avoid lazy lifecycle transitions during list assertions.
        jdbc.update("INSERT INTO exam_sessions (id, exam_id, created_by, code, title, status, starts_at, ends_at, "
                + "duration_minutes, visibility, opened_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, "
                + "TIMESTAMPTZ '2099-01-01 00:00:00Z', TIMESTAMPTZ '2099-01-01 02:00:00Z', 45, 'PUBLIC', "
                + "CASE WHEN ? = 'CLOSED' THEN TIMESTAMPTZ '2030-01-01 00:00:00Z' END, "
                + "CASE WHEN ? = 'CLOSED' THEN TIMESTAMPTZ '2030-01-01 02:00:00Z' END)",
                id, exam, creator, code, title, state, state, state);
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor subject(long user) {
        // Only the authenticated subject is mocked; no JWT authorities replace DB permissions.
        return jwt().jwt(token -> token.subject(String.valueOf(user)).claim("token_version", 0))
                .authorities(List.of());
    }

    private MockHttpServletRequestBuilder nested(long user) {
        return get("/api/exams/{id}/sessions", SOURCE_EXAM).with(subject(user));
    }

    private void readExam(long user) throws Exception {
        mvc.perform(get("/api/exams/{id}", SOURCE_EXAM).with(subject(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(SOURCE_EXAM));
    }

    private ResultActions expectIds(MockHttpServletRequestBuilder request, long... expected) throws Exception {
        ResultActions result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(expected.length));
        for (int i = 0; i < expected.length; i++) {
            result.andExpect(jsonPath("$.items[" + i + "].id").value(expected[i]));
        }
        return result;
    }

    private void denied(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(result -> assertThat(result.getResponse().getStatus())
                .as("parent-scoped read must deny access, not return 200 with an empty list").isIn(403, 404));
    }

    private void revoke(long user, String state) {
        assertThat(jdbc.update("UPDATE workspace_memberships SET status = ? WHERE workspace_id = ? AND user_id = ?",
                state, WORKSPACE, user)).isEqualTo(1);
        em.clear();
    }

    private Map<String, List<Map<String, Object>>> sourceSnapshot() {
        return Map.of(
                "exam", jdbc.queryForList("SELECT * FROM exams WHERE id = ?", SOURCE_EXAM),
                "sections", jdbc.queryForList("SELECT * FROM exam_sections WHERE exam_id = ? ORDER BY id", SOURCE_EXAM),
                "questions", jdbc.queryForList("SELECT * FROM exam_questions WHERE exam_id = ? ORDER BY id", SOURCE_EXAM),
                "options", jdbc.queryForList("SELECT o.* FROM exam_question_options o JOIN exam_questions q "
                        + "ON q.id = o.exam_question_id WHERE q.exam_id = ? ORDER BY o.id", SOURCE_EXAM),
                "sessions", jdbc.queryForList("SELECT * FROM exam_sessions WHERE exam_id = ? ORDER BY id", SOURCE_EXAM),
                "attempts", jdbc.queryForList("SELECT a.* FROM attempts a JOIN exam_sessions s "
                        + "ON s.id = a.exam_session_id WHERE s.exam_id = ? ORDER BY a.id", SOURCE_EXAM));
    }
}
