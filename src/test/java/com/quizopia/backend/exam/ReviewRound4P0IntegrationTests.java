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
 * blocking: an organizer can BLOCK a PUBLIC profile-less participant by
 * userId; blocked users lose discovery + new starts; unblock restores;
 * in-progress autosave/submit survive a block; legacy profile API and
 * workspace authorization hold.
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
        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

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

        private void addQuestionGraph(Long ownerWs, Long ownerUser, Long examId, Long subjectId, Long schoolId,
                        Long teacherProfileId) {
                Long bank = subjectId != null
                                ? insert("INSERT INTO question_banks (workspace_id, created_by_user_id, school_id, "
                                                + "subject_id, owner_teacher_id, code, name) VALUES (" + ownerWs + ","
                                                + ownerUser + "," + schoolId + "," + subjectId + ","
                                                + teacherProfileId + ",'B','B')")
                                : insert("INSERT INTO question_banks (workspace_id, created_by_user_id, code, name) "
                                                + "VALUES (" + ownerWs + "," + ownerUser + ",'B','B')");
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

        /** Org PUBLIC session fixture. Returns [sessionId, examId, wsId, schoolId]. */
        private Long[] orgPublicSession(Long organizer) {
                return orgPublicSession(organizer, "OPEN");
        }

        private Long[] orgPublicSession(Long organizer, String sessionStatus) {
                Long schoolId = insert("INSERT INTO schools (code, name) VALUES ('R4-SCH-" + System.nanoTime()
                                + "','S')");
                Long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId
                                + ",'GL','G')");
                Long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES ("
                                + schoolId + "," + gl + ",'SUB','S')");
                Long orgWs = insert("INSERT INTO organizations (legacy_school_id, code, name) VALUES ("
                                + schoolId + ",'R4-ORG','O')");
                Long wsId = insert("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION','O'," + orgWs + ")");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + organizer + ",'EDITOR','ACTIVE')");
                Long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES ("
                                + organizer + "," + schoolId + ",'TC')");
                Long examId = insert("INSERT INTO exams (workspace_id, created_by_user_id, school_id, subject_id, "
                                + "owner_teacher_id, code, title, status, published_at, total_points, "
                                + "instructions, duration_minutes, tf_matrix_scoring) VALUES (" + wsId + ","
                                + organizer + "," + schoolId + "," + subjectId + "," + tp + ",'R4E','E',"
                                + "'PUBLISHED',now(),1,'i',30,'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
                addQuestionGraph(wsId, organizer, examId, subjectId, schoolId, tp);
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, school_id, owner_teacher_id, code, "
                                + "title, status, starts_at, ends_at, created_by, visibility, max_attempts, "
                                + "duration_minutes, opened_at) VALUES (" + examId + "," + schoolId + "," + tp
                                + ",'R4SES','S','" + sessionStatus + "','" + STARTS + "','" + ENDS + "'," + organizer
                                + ",'PUBLIC',5,60," + ("OPEN".equals(sessionStatus) ? "now()" : "NULL") + ")");
                return new Long[] { sessionId, examId, wsId, schoolId };
        }

        /** Personal PUBLIC session fixture (owner without any profile). Returns [sessionId, personalWsId]. */
        private Long[] personalPublicSession(Long owner) {
                Long personalWs = jdbc.queryForObject(
                                "SELECT id FROM workspaces WHERE owner_user_id = " + owner
                                                + " AND workspace_type = 'PERSONAL'", Long.class);
                Long examId = insert("INSERT INTO exams (workspace_id, created_by_user_id, code, title, status, "
                                + "published_at, total_points, instructions, duration_minutes, tf_matrix_scoring) "
                                + "VALUES (" + personalWs + "," + owner + ",'R4PE','PE','PUBLISHED',now(),1,'i',30,"
                                + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
                addQuestionGraph(personalWs, owner, examId, null, null, null);
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, "
                                + "ends_at, created_by, visibility, max_attempts, duration_minutes, opened_at) "
                                + "VALUES (" + examId + ",'R4PSES','S','OPEN','" + STARTS + "','" + ENDS + "',"
                                + owner + ",'PUBLIC',5,60,now())");
                return new Long[] { sessionId, personalWs };
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
        void r8_personalWorkspacePublicSessionBlocking() throws Exception {
                Long owner = register("r8c-owner-" + System.nanoTime(), "TEACHER"); // no profile, personal ws
                Long[] fixture = personalPublicSession(owner);
                Long sessionId = fixture[0];

                Long publicUser = register("r8c-pub-" + System.nanoTime(), "STUDENT");
                startAttempt(sessionId, publicUser, "STUDENT");

                assertThat(blockByUser(sessionId, owner, publicUser))
                                .as("personal-workspace owner can block by userId (no TeacherProfile)")
                                .isEqualTo(200);
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(publicUser, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void r8_legacyStudentProfileApiStillWorks() throws Exception {
                Long organizer = register("r8d-org-" + System.nanoTime(), "TEACHER");
                // SCHEDULED so the legacy bulk-add (DRAFT/SCHEDULED only) works;
                // startAttempt lazy-opens it inside the window.
                Long[] fixture = orgPublicSession(organizer, "SCHEDULED");
                Long sessionId = fixture[0];
                Long schoolId = fixture[3];

                // Legacy school student + bulk add via studentProfileIds.
                Long student = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('r8d-stu-" + System.nanoTime() + "','r8d@t.com','h','R8D')");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + student
                                + ", id FROM roles WHERE code = 'STUDENT'");
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + student + "," + schoolId + ",'R8D')");

                mockMvc.perform(post("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentProfileIds\":[" + sp + "]}"))
                                .andExpect(status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                                "$.added").value(1));

                // Legacy list shows the participant with its profile linkage.
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // Block via the legacy by-participantId endpoint still works...
                Long participantRowId = jdbc.queryForObject(
                                "SELECT id FROM exam_session_participants WHERE exam_session_id = " + sessionId
                                                + " AND student_profile_id = " + sp, Long.class);
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
        void r8_revokedOrganizerCannotBlockUnblock() throws Exception {
                Long organizer = register("r8e-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];
                Long wsId = fixture[2];

                Long publicUser = register("r8e-pub-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, publicUser, "TEACHER");
                assertThat(blockByUser(sessionId, organizer, publicUser)).isEqualTo(200);

                jdbc.update("UPDATE workspace_memberships SET status = 'REMOVED' "
                                + "WHERE workspace_id = " + wsId + " AND user_id = " + organizer);
                em.clear(); // production revocation happens in another session

                Long other = register("r8e-other-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, other, "TEACHER");
                assertThat(blockByUser(sessionId, organizer, other))
                                .as("revoked organizer cannot block").isEqualTo(403);
                assertThat(unblockByUser(sessionId, organizer, publicUser))
                                .as("revoked organizer cannot unblock").isEqualTo(403);

                // Foreign teacher cannot block either.
                Long foreign = register("r8e-foreign-" + System.nanoTime(), "TEACHER");
                assertThat(blockByUser(sessionId, foreign, other))
                                .as("foreign teacher cannot block").isEqualTo(403);
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
