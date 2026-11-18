package com.quizopia.backend.question.ai;

import com.quizopia.backend.academic.domain.model.GradeLevel;
import com.quizopia.backend.academic.domain.model.Subject;
import com.quizopia.backend.academic.repository.GradeLevelRepository;
import com.quizopia.backend.academic.repository.SubjectRepository;
import com.quizopia.backend.identity.domain.model.User;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.question.ai.provider.MockAiQuizProvider;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.domain.model.QuestionBank;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.question.repository.QuestionBankRepository;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the AI-assisted authoring endpoints (Phase 5,
 * issue #113) using MockMvc + Testcontainers and the deterministic
 * {@link MockAiQuizProvider} (test profile). Exercises the full HTTP stack:
 * security filter chain, scoped exception handler, deny-by-default
 * authorization before parsing/provider calls, bounded extraction and the
 * authoring round trip. Nothing may persist questions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class AiAuthoringApiIntegrationTests {

    private static final String STATUS = "/api/question-banks/{bankId}/ai/authoring";
    private static final String EXTRACT = "/api/question-banks/{bankId}/ai/documents/extract";
    private static final String AUTHOR = "/api/question-banks/{bankId}/ai/author";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private GradeLevelRepository glRepo;
    @Autowired
    private SubjectRepository subjectRepo;
    @Autowired
    private QuestionBankRepository bankRepo;
    @Autowired
    private QuestionBankService bankService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long teacherUserId;
    private Long bankId;

    @BeforeEach
    void setUp() {
        MockAiQuizProvider.reset();
        User teacher = userRepo.saveAndFlush(
                new User("ai-api-teacher", "aiapi@test.com", "hash", "AI API Teacher"));
        teacherUserId = teacher.getId();
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", teacherUserId);
        GradeLevel gl = glRepo.saveAndFlush(new GradeLevel("GL-AIA", "Grade"));
        Subject subject = subjectRepo.saveAndFlush(new Subject(gl.getId(), "SUB-AIA", "Subject"));

        bankId = bankService.createBank(teacherUserId,
                        new CreateQuestionBankRequest("AI-API-BANK", "AI API Bank", null, subject.getId()))
                .id();
    }

    @AfterEach
    void cleanupCommittedTestData() {
        MockAiQuizProvider.reset();
        jdbc.update("DELETE FROM question_banks WHERE code LIKE 'AI-%'");
        jdbc.update("DELETE FROM subjects WHERE code = 'SUB-AIA'");
        jdbc.update("DELETE FROM grade_levels WHERE code = 'GL-AIA'");
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'ai-api-%')");
        jdbc.update("DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'ai-api-%')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'ai-api-%'");
    }

    private static JwtRequestPostProcessor jwtFor(Long userId) {
        return jwt().jwt(t -> t.subject(userId.toString()).claim("token_version", 0));
    }

    private static String jsonBody(String instruction, String markdown, String documentsJson, String historyJson) {
        return "{\"instruction\":" + jsonString(instruction)
                + ",\"currentMarkdown\":" + jsonString(markdown)
                + ",\"documents\":" + (documentsJson == null ? "null" : documentsJson)
                + ",\"history\":" + (historyJson == null ? "null" : historyJson)
                + "}";
    }

    private static String jsonString(String raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                + "\"";
    }

    private int questionCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questions WHERE question_bank_id = ?", Integer.class, bankId);
        return count == null ? 0 : count;
    }

    // ============================================================
    // Authorization matrix (deny by default; before parse/provider)
    // ============================================================

    @Test
    void unauthenticatedRequestsRejected() throws Exception {
        mockMvc.perform(get(STATUS, bankId)).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(EXTRACT, bankId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(AUTHOR, bankId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isUnauthorized());
        assertThat(MockAiQuizProvider.CALLS.get()).isZero();
    }

    @Test
    void studentRejectedOnAllEndpoints() throws Exception {
        User student = userRepo.saveAndFlush(
                new User("ai-api-student", "ai-api-student@test.com", "hash", "AI API Student"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'STUDENT'", student.getId());

        mockMvc.perform(get(STATUS, bankId).with(jwtFor(student.getId())))
                .andExpect(status().isForbidden());
        // The upload must be rejected BEFORE any byte is parsed.
        mockMvc.perform(multipart(EXTRACT, bankId)
                        .file(new MockMultipartFile("file", "lecture.txt", "text/plain",
                                "never parsed".getBytes(StandardCharsets.UTF_8)))
                        .with(jwtFor(student.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(student.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isForbidden());
        assertThat(MockAiQuizProvider.CALLS.get()).isZero();
    }

    @Test
    void unrelatedTeacherRejected() throws Exception {
        User other = userRepo.saveAndFlush(
                new User("ai-api-other", "ai-api-other@test.com", "hash", "AI Other Teacher"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE code = 'TEACHER'", other.getId());

        mockMvc.perform(get(STATUS, bankId).with(jwtFor(other.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(other.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isForbidden());
        assertThat(MockAiQuizProvider.CALLS.get()).isZero();
    }

    @Test
    void unknownBankIs404() throws Exception {
        mockMvc.perform(get(STATUS, 999_999).with(jwtFor(teacherUserId)))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // Status
    // ============================================================

    @Test
    void statusAvailableForBankOwner() throws Exception {
        mockMvc.perform(get(STATUS, bankId).with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.model").isNotEmpty());
    }

    // ============================================================
    // Extraction
    // ============================================================

    @Test
    void extractTxtForOwner() throws Exception {
        mockMvc.perform(multipart(EXTRACT, bankId)
                        .file(new MockMultipartFile("file", "lecture.txt", "text/plain",
                                "Networking lecture\nTCP handshake".getBytes(StandardCharsets.UTF_8)))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("lecture.txt"))
                .andExpect(jsonPath("$.text").value("Networking lecture\nTCP handshake"))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.charCount").value(32));
    }

    @Test
    void extractRejectsUnsupportedExtension() throws Exception {
        mockMvc.perform(multipart(EXTRACT, bankId)
                        .file(new MockMultipartFile("file", "virus.exe", "application/octet-stream",
                                new byte[] {1, 2, 3}))
                        .with(jwtFor(teacherUserId)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("AI_DOCUMENT_UNSUPPORTED_TYPE"));
    }

    @Test
    void extractRejectsOversizeUpload() throws Exception {
        MockMultipartFile big = new MockMultipartFile("file", "big.txt", "text/plain", new byte[8]) {
            @Override
            public long getSize() {
                return 6_000_000;
            }
        };
        mockMvc.perform(multipart(EXTRACT, bankId).file(big).with(jwtFor(teacherUserId)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AI_DOCUMENT_TOO_LARGE"));
    }

    // ============================================================
    // Authoring round trip
    // ============================================================

    @Test
    void authorReturnsValidCandidateWithoutPersisting() throws Exception {
        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("Create 5 questions about networking", "",
                                "[{\"name\":\"lecture.txt\",\"text\":\"TCP basics\"}]", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.markdown").value(MockAiQuizProvider.DEFAULT_MARKDOWN.stripTrailing()))
                .andExpect(jsonPath("$.reply").isNotEmpty())
                .andExpect(jsonPath("$.diagnostics.length()").value(0));

        assertThat(MockAiQuizProvider.CALLS.get()).isEqualTo(1);
        // AI authoring never persists — only the explicit import endpoint does.
        assertThat(questionCount()).isZero();
    }

    @Test
    void authorInvalidCandidateRunsBoundedRepairAndReportsDiagnostics() throws Exception {
        MockAiQuizProvider.NEXT_MARKDOWN.set("Question 3 [SINGLE_CHOICE]: broken numbering\n*A. one\nB. two\nC. three\nD. four\n");

        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.diagnostics[0].code").isNotEmpty());

        // Exactly one repair attempt.
        assertThat(MockAiQuizProvider.CALLS.get()).isEqualTo(2);
        assertThat(questionCount()).isZero();
    }

    @Test
    void authorRejectsBlankInstruction() throws Exception {
        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(" ", "", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_AUTHORING_REQUEST_INVALID"));
        assertThat(MockAiQuizProvider.CALLS.get()).isZero();
    }

    @Test
    void authorRejectsTooManyDocuments() throws Exception {
        String docs = "[{\"name\":\"a.txt\",\"text\":\"a\"},{\"name\":\"b.txt\",\"text\":\"b\"},"
                + "{\"name\":\"c.txt\",\"text\":\"c\"},{\"name\":\"d.txt\",\"text\":\"d\"},"
                + "{\"name\":\"e.txt\",\"text\":\"e\"},{\"name\":\"f.txt\",\"text\":\"f\"}]";
        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", docs, null)))
                .andExpect(status().isBadRequest());
        assertThat(MockAiQuizProvider.CALLS.get()).isZero();
    }

    @Test
    void authorMapsProviderRateLimitTo429() throws Exception {
        MockAiQuizProvider.NEXT_FAILURE.set(
                new com.quizopia.backend.question.ai.provider.AiProviderException(
                        com.quizopia.backend.question.ai.provider.AiProviderException.Kind.RATE_LIMITED, "429"));

        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_RATE_LIMITED"));
    }

    @Test
    void authorMapsProviderTimeoutTo504() throws Exception {
        MockAiQuizProvider.NEXT_FAILURE.set(
                new com.quizopia.backend.question.ai.provider.AiProviderException(
                        com.quizopia.backend.question.ai.provider.AiProviderException.Kind.TIMEOUT, "t/o"));

        mockMvc.perform(post(AUTHOR, bankId)
                        .with(jwtFor(teacherUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("gen", "", null, null)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_TIMEOUT"));
    }
}
