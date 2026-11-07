package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #4 review round 4 reproduction (P0-R8) — user-based participant
 * blocking: a session owner can BLOCK a PUBLIC participant by userId;
 * blocked users lose discovery + new starts; unblock restores;
 * in-progress autosave/submit survive a block; creator-only
 * authorization holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ReviewRound4P0IntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private JdbcTemplate jdbc;


        private static final Instant STARTS = Instant.now().minusSeconds(60);
        private static final Instant ENDS = Instant.now().plusSeconds(3600);

        private static tools.jackson.databind.json.JsonMapper mapper() {
                return tools.jackson.databind.json.JsonMapper.builder().build();
        }

        private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwt(
                        Long userId, String... roles) {
                var authorities = java.util.Arrays.stream(roles)
                                .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                                "ROLE_" + r))
                                .toList();
                return SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(t -> t.subject(userId.toString()).claim("token_version", 0))
                                .authorities(authorities.toArray(
                                                new org.springframework.security.core.GrantedAuthority[0]));
        }

        private Long register(String username, String accountType) throws Exception {

                MvcResult result = mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"" + username + "\","
                                                + "\"phone\":\"0900000088\","
                                                + "\"accountType\":\"STUDENT\"}"))
                                .andExpect(status().isCreated())
                                .andReturn();
                JsonNode body = mapper().readTree(result.getResponse().getContentAsString());
                Long userId = body.get("id").asLong();
                if ("TEACHER".equals(accountType)) {
                        // Identity-M1: the public form is STUDENT-only; tests emulate the
                        // teacher capability grant the way M2's self-upgrade will.
                        jdbc.update("INSERT INTO user_roles (user_id, role_id, assigned_at) "
                                        + "SELECT " + userId + ", id, now() FROM roles WHERE code = 'TEACHER'");
                }
                return userId;
        }

        private Long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }

        private void addQuestionGraph(Long ownerUser, Long examId, Long subjectId) {
                Long bank = subjectId != null
                                ? insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) "
                                                + "VALUES (" + ownerUser + "," + subjectId + ",'B','B')")
                                : insert("INSERT INTO question_banks (created_by_user_id, code, name) "
                                                + "VALUES (" + ownerUser + ",'B','B')");
                Long q = insert("INSERT INTO questions (question_bank_id, code, status, created_by) VALUES ("
                                + bank + ",'q','ACTIVE'," + ownerUser + ")");
                Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                                + "content, difficulty, default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','q?','MEDIUM',1,'{}'::jsonb," + ownerUser + ")");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + v + ",'" + o[0] + "','o'," + o[1]
                                        + "," + o[2] + ")");
                }
                Long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId
                                + ",'S',0)");
                Long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                                + "source_question_version_id, question_code, question_type, content, "
                                + "default_points, position, metadata) VALUES (" + examId + "," + sec + "," + q
                                + "," + v + ",'q','SINGLE_CHOICE','q?',1,0,'{}'::jsonb)");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO exam_question_options (exam_question_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + eq + ",'" + o[0] + "','o'," + o[1]
                                        + "," + o[2] + ")");
                }
        }

        /** User-owned PUBLIC session fixture. Returns [sessionId, examId]. */
        private Long[] orgPublicSession(Long organizer) {
                return orgPublicSession(organizer, "OPEN");
        }

        private Long[] orgPublicSession(Long organizer, String sessionStatus) {
                Long examId = insert("INSERT INTO exams (created_by_user_id, code, title, status, "
                                + "published_at, total_points, instructions, duration_minutes, "
                                + "tf_matrix_scoring) VALUES (" + organizer + ",'R4E','E',"
                                + "'PUBLISHED',now(),1,'i',30,'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
                addQuestionGraph(organizer, examId, null);
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, "
                                + "ends_at, created_by, visibility, max_attempts, duration_minutes, opened_at) "
                                + "VALUES (" + examId + ",'R4SES','S','" + sessionStatus + "','" + STARTS
                                + "','" + ENDS + "'," + organizer + ",'PUBLIC',5,60,"
                                + ("OPEN".equals(sessionStatus) ? "now()" : "NULL") + ")");
                return new Long[] { sessionId, examId };
        }

        /** Subject-less PUBLIC session fixture owned by a plain user (no catalog metadata). Returns [sessionId, examId]. */
        private Long[] personalPublicSession(Long owner) {
                Long examId = insert("INSERT INTO exams (created_by_user_id, code, title, status, "
                                + "published_at, total_points, instructions, duration_minutes, tf_matrix_scoring) "
                                + "VALUES (" + owner + ",'R4PE','PE','PUBLISHED',now(),1,'i',30,"
                                + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
                addQuestionGraph(owner, examId, null);
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, "
                                + "ends_at, created_by, visibility, max_attempts, duration_minutes, opened_at) "
                                + "VALUES (" + examId + ",'R4PSES','S','OPEN','" + STARTS + "','" + ENDS + "',"
                                + owner + ",'PUBLIC',5,60,now())");
                return new Long[] { sessionId, examId };
        }

        /** Starts an attempt over HTTP; returns attemptId (2xx). */
        private Long startAttempt(Long sessionId, Long userId, String role) throws Exception {
                MvcResult start = mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(userId, role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful())
                                .andReturn();
                return mapper().readTree(start.getResponse().getContentAsString()).get("attemptId").asLong();
        }

        /** Blocks the user on the session by USER id (new API). Returns response status. */
        private int blockByUser(Long sessionId, Long organizer, Long targetUserId) throws Exception {
                MvcResult res = mockMvc.perform(post("/api/exam-sessions/{id}/participants/by-user/{uid}/block",
                                sessionId, targetUserId).with(jwt(organizer, "TEACHER")))
                                .andReturn();
                return res.getResponse().getStatus();
        }

        private int unblockByUser(Long sessionId, Long organizer, Long targetUserId) throws Exception {
                MvcResult res = mockMvc.perform(post("/api/exam-sessions/{id}/participants/by-user/{uid}/unblock",
                                sessionId, targetUserId).with(jwt(organizer, "TEACHER")))
                                .andReturn();
                return res.getResponse().getStatus();
        }

        // =====================================================================
        // Core scenario — org PUBLIC session, external profile-less user
        // =====================================================================

        @Test
        void r8_blockPublicProfileLessUserByUserId() throws Exception {
                Long organizer = register("r8-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                // TEACHER-only public user WITHOUT profile starts (maxAttempts=5).
                Long publicUser = register("r8-pub-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, publicUser, "TEACHER");

                // Block by userId (new API) — must succeed for a profile-less user.
                int blockStatus = blockByUser(sessionId, organizer, publicUser);
                assertThat(blockStatus).as("organizer can block a profile-less PUBLIC participant by userId")
                                .isEqualTo(200);

                // Discovery hides the session.
                MvcResult available = mockMvc.perform(get("/api/exam-sessions/available")
                                .with(jwt(publicUser, "TEACHER")))
                                .andExpect(status().isOk()).andReturn();
                for (JsonNode item : mapper().readTree(available.getResponse().getContentAsString())
                                .get("items")) {
                        assertThat(item.get("sessionId").asLong()).isNotEqualTo(sessionId);
                }

                // New starts denied even though maxAttempts allows more.
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(publicUser, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());

                // Unblock restores eligibility.
                int unblockStatus = unblockByUser(sessionId, organizer, publicUser);
                assertThat(unblockStatus).isEqualTo(200);
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(publicUser, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful());
        }

        @Test
        void r8_inProgressAttemptSurvivesBlockAutosaveSubmit() throws Exception {
                Long organizer = register("r8b-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                Long publicUser = register("r8b-pub-" + System.nanoTime(), "TEACHER");
                Long attemptId = startAttempt(sessionId, publicUser, "TEACHER");

                assertThat(blockByUser(sessionId, organizer, publicUser)).isEqualTo(200);

                // In-progress attempt: autosave still allowed (documented policy).
                Long attemptQuestionId = jdbc.queryForObject(
                                "SELECT id FROM attempt_questions WHERE attempt_id = " + attemptId
                                                + " ORDER BY display_order LIMIT 1", Long.class);
                mockMvc.perform(put("/api/attempts/{id}/answers", attemptId)
                                .with(jwt(publicUser, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"attemptQuestionId\":" + attemptQuestionId
                                                + ",\"answerPayload\":{\"selectedOptionKey\":\"A\"},"
                                                + "\"sequenceNumber\":1}"))
                                .andExpect(status().isOk());

                // Submit still allowed (completing the running attempt).
                mockMvc.perform(post("/api/attempts/{id}/submit", attemptId)
                                .with(jwt(publicUser, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"submissionIdempotencyKey\":\"r8-" + attemptId + "\"}"))
                                .andExpect(status().isOk());

                // New start after submit still denied while blocked.
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(publicUser, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void r8_plainUserOwnedPublicSessionBlocking() throws Exception {
                Long owner = register("r8c-owner-" + System.nanoTime(), "TEACHER"); // plain user, no catalog metadata
                Long[] fixture = personalPublicSession(owner);
                Long sessionId = fixture[0];

                Long publicUser = register("r8c-pub-" + System.nanoTime(), "STUDENT");
                startAttempt(sessionId, publicUser, "STUDENT");

                assertThat(blockByUser(sessionId, owner, publicUser))
                                .as("session owner can block by userId")
                                .isEqualTo(200);
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(publicUser, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void r8_participantRowBlockAndByUserUnblockAreLinked() throws Exception {
                Long organizer = register("r8d-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer, "SCHEDULED");
                Long sessionId = fixture[0];

                // Participant row created directly (user-based).
                Long student = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('r8d-stu-" + System.nanoTime() + "','r8d@t.com','h','R8D')");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + student
                                + ", id FROM roles WHERE code = 'STUDENT'");
                Long participantRowId = insert("INSERT INTO exam_session_participants (exam_session_id, "
                                + "user_id, added_by) VALUES (" + sessionId + "," + student + "," + organizer + ")");

                // List shows the participant.
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // Block via the by-participantId endpoint...
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/block", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
                // ...and the same row is reachable by userId (single source of truth).
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/by-user/{uid}/unblock", sessionId,
                                student).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful());
        }

        @Test
        void r8_nonCreatorCannotBlockUnblock() throws Exception {
                Long organizer = register("r8e-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                Long publicUser = register("r8e-pub-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, publicUser, "TEACHER");
                assertThat(blockByUser(sessionId, organizer, publicUser)).isEqualTo(200);

                Long other = register("r8e-other-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, other, "TEACHER");

                // Foreign teacher (not the session creator) cannot moderate.
                Long foreign = register("r8e-foreign-" + System.nanoTime(), "TEACHER");
                assertThat(blockByUser(sessionId, foreign, other))
                                .as("non-creator teacher cannot block").isEqualTo(403);
                assertThat(unblockByUser(sessionId, foreign, publicUser))
                                .as("non-creator teacher cannot unblock").isEqualTo(403);
        }

        @Test
        void r8_blockUnknownUserRejected() throws Exception {
                Long organizer = register("r8f-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                // Blocking a user who never participated and was never assigned → 404.
                Long stranger = register("r8f-stranger-" + System.nanoTime(), "TEACHER");
                assertThat(blockByUser(sessionId, organizer, stranger)).isEqualTo(404);
        }

        @Test
        void r8_repeatedBlockIsIdempotent() throws Exception {
                Long organizer = register("r8g-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                Long publicUser = register("r8g-pub-" + System.nanoTime(), "STUDENT");
                startAttempt(sessionId, publicUser, "STUDENT");

                assertThat(blockByUser(sessionId, organizer, publicUser)).isEqualTo(200);
                assertThat(blockByUser(sessionId, organizer, publicUser)).isEqualTo(200); // idempotent
                assertThat(unblockByUser(sessionId, organizer, publicUser)).isEqualTo(200);
                assertThat(unblockByUser(sessionId, organizer, publicUser)).isEqualTo(200); // idempotent
        }
}
