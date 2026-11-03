package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptQueryService;
import com.quizopia.backend.attempt.application.AttemptResultService;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.application.AttemptSubmitService;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.exam.application.ExamService;
import com.quizopia.backend.exam.application.ExamSessionService;
import com.quizopia.backend.exam.domain.model.SessionVisibility;
import com.quizopia.backend.exam.dto.CreateExamSessionRequest;
import com.quizopia.backend.exam.dto.CreateExamRequest;
import com.quizopia.backend.exam.dto.PublishedExamSummary;
import com.quizopia.backend.exam.dto.TeacherExamEditorResponse;
import com.quizopia.backend.exam.dto.UpdateDraftCompositionRequest;
import com.quizopia.backend.question.application.QuestionBankService;
import com.quizopia.backend.question.dto.CreateQuestionBankRequest;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.attempt.dto.AvailableSessionsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-1 reproduction (issue #4): a newly registered user with NO school and
 * NO StudentProfile must be able to create a Personal-Workspace Exam and,
 * with the SAME account, participate in a PUBLIC session end-to-end:
 * discover → start → autosave → submit → result → history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class PublicLearnerEndToEndIntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private JdbcTemplate jdbc;
        @Autowired
        private QuestionBankService bankService;
        @Autowired
        private ExamService examService;
        @Autowired
        private ExamSessionService sessionService;
        @Autowired
        private AttemptService attemptService;
        @Autowired
        private AttemptSubmitService submitService;
        @Autowired
        private com.quizopia.backend.attempt.application.AttemptAutosaveService autosaveService;
        @Autowired
        private AttemptResultService resultService;
        @Autowired
        private AttemptQueryService queryService;

        /** Registers a public user via the public API; returns the user id. */
        private Long registerPublicUser(String username) throws Exception {
                MvcResult result = mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"Public Creator\","
                                                + "\"phone\":\"0900000010\","
                                                + "\"accountType\":\"STUDENT\"}"))
                                .andExpect(status().isCreated())
                                .andReturn();
                JsonNode body = tools.jackson.databind.json.JsonMapper.builder().build()
                                .readTree(result.getResponse().getContentAsString());
                Long userId = body.get("id").asLong();
                // Identity-M1: teacher capability is granted post-registration
                // (M2 self-upgrade semantics), never self-selected at signup.
                jdbc.update("INSERT INTO user_roles (user_id, role_id, assigned_at) "
                                + "SELECT " + userId + ", id, now() FROM roles WHERE code = 'TEACHER'");
                return userId;
        }

        @Test
        void publicUserCreatesExamAndCompletesPublicSessionEndToEnd() throws Exception {
                Long userId = registerPublicUser("e2e-public-" + System.nanoTime());
                // No school, no teacher profile, no student profile.
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM student_profiles WHERE user_id = " + userId, Long.class))
                                .isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM teacher_profiles WHERE user_id = " + userId, Long.class))
                                .isZero();

                // --- Creator side: personal bank → question → exam → publish → session.
                Long bankId = bankService.createBank(userId,
                                new CreateQuestionBankRequest(null, "E2E-BANK", "E2E Bank", null, null)).id();
                Long questionId = jdbc.queryForObject(
                                "INSERT INTO questions (question_bank_id, code, status, created_by) "
                                                + "VALUES (" + bankId + ",'q1','ACTIVE'," + userId + ") RETURNING id",
                                Long.class);
                Long versionId = jdbc.queryForObject(
                                "INSERT INTO question_versions (question_id, version_number, question_type, "
                                                + "content, difficulty, default_points, metadata, created_by) "
                                                + "VALUES (" + questionId + ",1,'SINGLE_CHOICE','e2e q','MEDIUM',1,"
                                                + "'{}'::jsonb," + userId + ") RETURNING id",
                                Long.class);
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 },
                                { "C", false, 2 }, { "D", false, 3 } }) {
                        jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + versionId + ",'" + o[0]
                                        + "','opt'," + o[1] + "," + o[2] + ")");
                }

                Long examId = examService.createExam(userId,
                                new CreateExamRequest(null, null, null, "E2E-EX", "E2E Exam", null)).id();
                TeacherExamEditorResponse composed = examService.updateDraftComposition(userId, examId,
                                new UpdateDraftCompositionRequest(0, 30, "good luck", List.of(
                                                new UpdateDraftCompositionRequest.CompositionSectionRequest(
                                                                0, "S", null,
                                                                List.of(new UpdateDraftCompositionRequest.CompositionQuestionRequest(
                                                                                questionId, 0, null))))));
                assertThat(composed.status()).isEqualTo("DRAFT");
                PublishedExamSummary published = examService.publishExam(userId, examId, null);
                assertThat(published.status()).isEqualTo("PUBLISHED");

                Instant starts = Instant.now().minusSeconds(60);
                Instant ends = Instant.now().plusSeconds(3600);
                Long                 sessionId = sessionService.createSession(userId, examId,
                                new CreateExamSessionRequest(examId, "E2E-SES", "E2E Session",
                                                starts, ends, 1, 30, SessionVisibility.PUBLIC, null))
                                .id();
                // DRAFT → SCHEDULED (students lazily open it on access).
                sessionService.scheduleSession(userId, examId, sessionId);

                // --- Learner side: SAME account, no profile.
                AvailableSessionsResponse available = attemptService.getAvailableSessions(userId);
                assertThat(available.items())
                                .anySatisfy(i -> {
                                        assertThat(i.sessionId()).isEqualTo(sessionId);
                                        assertThat(i.canStartNow()).isTrue();
                                });

                StartAttemptResponse start = attemptService.startAttempt(userId, sessionId,
                                new com.quizopia.backend.attempt.dto.StartAttemptRequest(null));
                Long attemptId = start.attemptId();
                assertThat(start.questions()).hasSize(1);

                // Autosave the selected answer (single-choice payload shape:
                // {"selectedOptionKey":"A"}).
                Long attemptQuestionId = start.questions().get(0).attemptQuestionId();
                var payload = tools.jackson.databind.json.JsonMapper.builder().build()
                                .readTree("{\"selectedOptionKey\":\"A\"}");
                autosaveService.saveAnswer(userId, attemptId,
                                new com.quizopia.backend.attempt.dto.SaveAnswerRequest(
                                                attemptQuestionId, null, payload, 1, null));

                // Submit with an idempotency key.
                var submit = submitService.submitAttempt(userId, attemptId,
                                new com.quizopia.backend.attempt.dto.SubmitRequest("e2e-key-1"));
                assertThat(submit.status()).isEqualTo("SUBMITTED");

                // Result readable by the same (profile-less) user.
                var result = resultService.getAttemptResult(userId, attemptId);
                assertThat(result.attemptId()).isEqualTo(attemptId);

                // History lists the attempt.
                var history = queryService.getMyAttempts(userId, 0, 20);
                assertThat(history.items())
                                .anySatisfy(i -> assertThat(i.attemptId()).isEqualTo(attemptId));

                // Idempotent resubmit replays the cached response.
                var replay = submitService.submitAttempt(userId, attemptId,
                                new com.quizopia.backend.attempt.dto.SubmitRequest("e2e-key-1"));
                assertThat(replay.attemptId()).isEqualTo(attemptId);
        }
}
