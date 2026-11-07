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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #4 review round 3 reproductions (P0-R5..R7) — real HTTP paths.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ReviewRound3P0IntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private JdbcTemplate jdbc;

        private static final Instant STARTS = Instant.now().minusSeconds(60);
        private static final Instant ENDS = Instant.now().plusSeconds(3600);

        private static tools.jackson.databind.json.JsonMapper mapper() {
                return tools.jackson.databind.json.JsonMapper.builder().build();
        }

        /**
         * MockMvc's jwt() post-processor does not run the production converter,
         * so effective authorities are attached explicitly (mirroring DB roles).
         */
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

        /** User-owned exam + session fixture. Returns [sessionId, examId]. */
        private Long[] orgExamSession(Long orgUserId, String visibility) {
                Long bank = insert("INSERT INTO question_banks (created_by_user_id, code, name) VALUES ("
                                + orgUserId + ",'B','B')");
                Long q = insert("INSERT INTO questions (question_bank_id, code, status, created_by) VALUES ("
                                + bank + ",'q','ACTIVE'," + orgUserId + ")");
                Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                                + "content, difficulty, default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','q?','MEDIUM',1,'{}'::jsonb," + orgUserId + ")");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + v + ",'" + o[0] + "','o'," + o[1]
                                        + "," + o[2] + ")");
                }
                Long examId = insert("INSERT INTO exams (created_by_user_id, code, title, status, "
                                + "published_at, total_points, instructions, duration_minutes, "
                                + "tf_matrix_scoring) VALUES (" + orgUserId + ",'R3E','E',"
                                + "'PUBLISHED',now(),1,'i',30,'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
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
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, "
                                + "ends_at, created_by, visibility, max_attempts, duration_minutes, opened_at) "
                                + "VALUES (" + examId + ",'R3SES','S','OPEN','" + STARTS + "','" + ENDS + "',"
                                + orgUserId + ",'" + visibility + "',5,60,now())");
                return new Long[] { sessionId, examId };
        }

        /** Starts + submits an attempt over HTTP for a TEACHER-only public user; returns [userId, attemptId]. */
        private Long[] publicUserSubmits(Long sessionId, String username) throws Exception {
                Long userId = register(username, "TEACHER");
                MvcResult start = mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(userId, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful())
                                .andReturn();
                JsonNode startBody = mapper().readTree(start.getResponse().getContentAsString());
                return new Long[] { userId, startBody.get("attemptId").asLong() };
        }

        // =====================================================================
        // P0-R5 — roster includes actual attempt participants (profile-less OK)
        // =====================================================================

        @Test
        void p0r5_rosterIncludesProfileLessPublicAttemptUser() throws Exception {
                Long organizer = register("r5-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(organizer, "PUBLIC");
                Long sessionId = fixture[0];

                Long[] submitted = publicUserSubmits(sessionId, "r5-pub-" + System.nanoTime());
                Long publicUserId = submitted[0];

                MvcResult roster = mockMvc.perform(get("/api/exam-sessions/{id}/roster", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andReturn();
                JsonNode items = mapper().readTree(roster.getResponse().getContentAsString());
                boolean found = false;
                for (JsonNode item : items) {
                        if (item.get("userId").asLong() == publicUserId) {
                                found = true;
                                assertThat(item.get("displayName").asString()).isNotBlank();
                        }
                }
                assertThat(found).as("roster must include the profile-less PUBLIC attempt user").isTrue();
        }

        @Test
        void p0r5_rosterDoesNotIncludeUserBeforeParticipation() throws Exception {
                Long organizer = register("r5b-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(organizer, "PUBLIC");
                Long sessionId = fixture[0];

                // A registered user who has NOT attempted must not appear.
                Long bystander = register("r5b-bystander-" + System.nanoTime(), "TEACHER");

                MvcResult roster = mockMvc.perform(get("/api/exam-sessions/{id}/roster", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andReturn();
                JsonNode items = mapper().readTree(roster.getResponse().getContentAsString());
                for (JsonNode item : items) {
                        assertThat(item.get("userId").asLong())
                                        .as("bystander must not be a roster member before participating")
                                        .isNotEqualTo(bystander);
                }
        }

        // =====================================================================
        // P0-R6 — participant management is creator-only (user-centric policy)
        // =====================================================================

        /** Registers a user and inserts a user-based participant row on the session. */
        private Long addUserParticipant(Long sessionId, String tag, Long addedBy) {
                Long student = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('" + tag + "-" + System.nanoTime() + "','" + tag + "@t.com','h','"
                                + tag + "')");
                insert("INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) VALUES ("
                                + sessionId + "," + student + "," + addedBy + ")");
                return student;
        }

        @Test
        void p0r6_nonCreatorDeniedParticipantManagement() throws Exception {
                Long organizer = register("r6b-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(organizer, "CLASS_RESTRICTED");
                Long sessionId = fixture[0];
                Long studentUserId = addUserParticipant(sessionId, "r6bstu", organizer);
                Long participantRowId = jdbc.queryForObject(
                                "SELECT id FROM exam_session_participants WHERE user_id = " + studentUserId
                                                + " AND exam_session_id = " + sessionId, Long.class);

                // The creator manages fine.
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/block", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/unblock", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // Any other teacher (not the session creator) is denied.
                Long foreign = register("r6b-foreign-" + System.nanoTime(), "TEACHER");
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(foreign, "TEACHER")))
                                .andExpect(status().isForbidden());
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/block", sessionId,
                                participantRowId).with(jwt(foreign, "TEACHER")))
                                .andExpect(status().isForbidden());
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/unblock", sessionId,
                                participantRowId).with(jwt(foreign, "TEACHER")))
                                .andExpect(status().isForbidden());
        }

        @Test
        void p0r6_sessionWithoutParticipantsListsEmpty() throws Exception {
                // A user-created session with no participant rows: listing is a
                // clean 200 with an empty page (never an NPE 500).
                Long owner = register("r6c-owner-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(owner, "PUBLIC");
                Long sessionId = fixture[0];

                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(owner, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(0));
        }

        // =====================================================================
        // P0-R7 — BLOCKED participant denied discovery + new starts
        // =====================================================================

        @Test
        void p0r7_blockedParticipantDeniedDiscoveryAndStart() throws Exception {
                Long organizer = register("r7-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(organizer, "PUBLIC");
                Long sessionId = fixture[0];

                Long student = register("r7-stu-" + System.nanoTime(), "STUDENT");
                insert("INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) VALUES ("
                                + sessionId + "," + student + "," + organizer + ")");
                Long participantRowId = jdbc.queryForObject(
                                "SELECT id FROM exam_session_participants WHERE user_id = " + student
                                                + " AND exam_session_id = " + sessionId, Long.class);

                // Block the participant (organizer, via HTTP).
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/block", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // Discovery must not list the session for the blocked user.
                MvcResult available = mockMvc.perform(get("/api/exam-sessions/available")
                                .with(jwt(student, "STUDENT")))
                                .andExpect(status().isOk()).andReturn();
                JsonNode items = mapper().readTree(available.getResponse().getContentAsString()).get("items");
                for (JsonNode item : items) {
                        assertThat(item.get("sessionId").asLong())
                                        .as("blocked participant must not discover the session")
                                        .isNotEqualTo(sessionId);
                }

                // New start denied.
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());

                // Unblock restores eligibility.
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/unblock", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful());
        }

        @Test
        void p0r7_blockedDoesNotTouchInProgressAttemptData() throws Exception {
                Long organizer = register("r7b-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgExamSession(organizer, "PUBLIC");
                Long sessionId = fixture[0];

                Long student = register("r7b-stu-" + System.nanoTime(), "STUDENT");
                insert("INSERT INTO exam_session_participants (exam_session_id, user_id, added_by) VALUES ("
                                + sessionId + "," + student + "," + organizer + ")");
                Long participantRowId = jdbc.queryForObject(
                                "SELECT id FROM exam_session_participants WHERE user_id = " + student
                                                + " AND exam_session_id = " + sessionId, Long.class);

                // Start an attempt (IN_PROGRESS).
                MvcResult start = mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful()).andReturn();
                Long attemptId = mapper().readTree(start.getResponse().getContentAsString())
                                .get("attemptId").asLong();

                // Block the participant mid-attempt.
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/{pid}/block", sessionId,
                                participantRowId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // Attempt row + data preserved untouched.
                Long count = jdbc.queryForObject(
                                "SELECT count(*) FROM attempts WHERE id = " + attemptId
                                                + " AND status = 'IN_PROGRESS'", Long.class);
                assertThat(count).isEqualTo(1L);

                // New starts still denied while blocked; resume of the in-progress
                // attempt is policy: allowed to finish (documented decision).
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(student, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }
}
