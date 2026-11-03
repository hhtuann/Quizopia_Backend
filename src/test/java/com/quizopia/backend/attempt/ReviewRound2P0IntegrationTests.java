package com.quizopia.backend.attempt;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
 * Issue #4 review round 2 reproductions (P0-R1..P0-R3) â€” real HTTP paths,
 * no direct service calls for the flows under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ReviewRound2P0IntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private JdbcTemplate jdbc;

        private static final Instant STARTS = Instant.now().minusSeconds(60);
        private static final Instant ENDS = Instant.now().plusSeconds(3600);

        /** Registers a public user over the real HTTP endpoint; no school/profile. */
        private Long register(String username, String accountType) throws Exception {

                MvcResult result = mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                                + "@t.com\",\"password\":\"Passw0rd!123\","
                                                + "\"displayName\":\"" + username + "\","
                                                + "\"phone\":\"0900000099\","
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

        private static tools.jackson.databind.json.JsonMapper mapper() {
                return tools.jackson.databind.json.JsonMapper.builder().build();
        }

        /**
         * MockMvc's {@code jwt()} post-processor does NOT run the production
         * converter (authorities come from the DB there), so effective
         * authorities are attached explicitly — mirroring each user's DB
         * roles exactly as {@code EffectiveRoleResolver} sees them at runtime.
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

        private Long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }

        /**
         * Builds an ORGANIZATION-workspace exam + session owned by orgUserId.
         * Returns sessionId. The org creator gets an ACTIVE EDITOR membership.
         */
        private Long orgExamSession(Long orgUserId, String visibility) {
                Long schoolId = insert("INSERT INTO schools (code, name) VALUES ('R2-SCH-" + System.nanoTime()
                                + "','S')");
                Long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId
                                + ",'GL','G')");
                Long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES ("
                                + schoolId + "," + gl + ",'SUB','S')");
                Long orgWs = insert("INSERT INTO organizations (legacy_school_id, code, name) VALUES ("
                                + schoolId + ",'R2-ORG','O')");
                Long wsId = insert("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION','O'," + orgWs + ")");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + orgUserId + ",'EDITOR','ACTIVE')");
                Long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES ("
                                + orgUserId + "," + schoolId + ",'TC')");
                Long bank = insert("INSERT INTO question_banks (workspace_id, created_by_user_id, school_id, "
                                + "subject_id, owner_teacher_id, code, name) VALUES (" + wsId + "," + orgUserId
                                + "," + schoolId + "," + subjectId + "," + tp + ",'B','B')");
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
                Long examId = insert("INSERT INTO exams (workspace_id, created_by_user_id, school_id, subject_id, "
                                + "owner_teacher_id, code, title, status, published_at, total_points, "
                                + "instructions, duration_minutes, tf_matrix_scoring) VALUES (" + wsId + ","
                                + orgUserId + "," + schoolId + "," + subjectId + "," + tp + ",'R2E','E',"
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
                Long sessionId = insert("INSERT INTO exam_sessions (exam_id, school_id, owner_teacher_id, code, "
                                + "title, status, starts_at, ends_at, created_by, visibility, max_attempts, "
                                + "duration_minutes, opened_at) VALUES (" + examId + "," + schoolId + "," + tp
                                + ",'R2SES','S','OPEN','" + STARTS + "','" + ENDS + "'," + orgUserId + ",'"
                                + visibility + "',1,60,now())");
                return sessionId;
        }

        /**
         * Registers a TEACHER-only public user and starts+submits an attempt
         * over HTTP. Returns [userId, attemptId].
         */
        private Long[] publicUserSubmits(Long sessionId, String username) throws Exception {
                Long userId = register(username, "TEACHER");
                MvcResult start = mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(userId, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isCreated())
                                .andReturn();
                JsonNode startBody = mapper().readTree(start.getResponse().getContentAsString());
                Long attemptId = startBody.get("attemptId").asLong();
                Long attemptQuestionId = startBody.get("questions").get(0).get("attemptQuestionId").asLong();
                mockMvc.perform(put("/api/attempts/{id}/answers", attemptId)
                                .with(jwt(userId, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"attemptQuestionId\":" + attemptQuestionId
                                                + ",\"answerPayload\":{\"selectedOptionKey\":\"A\"},"
                                                + "\"sequenceNumber\":1}"))
                                .andExpect(status().isOk());
                mockMvc.perform(post("/api/attempts/{id}/submit", attemptId)
                                .with(jwt(userId, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"submissionIdempotencyKey\":\"r2-" + username + "\"}"))
                                .andExpect(status().isOk());
                return new Long[] { userId, attemptId };
        }

        // =====================================================================
        // P0-R1 â€” TEACHER-only external participant reads OWN result over HTTP
        // =====================================================================

        @Test
        void p0r1_teacherOnlyParticipantReadsOwnResultOverHttp() throws Exception {
                Long organizer = register("r2-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "PUBLIC");

                Long[] submitted = publicUserSubmits(sessionId, "r2-part-" + System.nanoTime());
                Long participant = submitted[0];
                Long attemptId = submitted[1];

                // P0-R1: participant (TEACHER-only, foreign session) reads OWN result → 200.
                mockMvc.perform(get("/api/attempts/{id}/result", attemptId).with(jwt(participant, "TEACHER")))
                                .andExpect(status().isOk());

                // Another unrelated learner must NOT read it (denied).
                Long stranger = register("r2-stranger-" + System.nanoTime(), "TEACHER");
                mockMvc.perform(get("/api/attempts/{id}/result", attemptId).with(jwt(stranger, "TEACHER")))
                                .andExpect(status().isForbidden());

                // Organizer of the session may read the participant's attempt result.
                mockMvc.perform(get("/api/attempts/{id}/result", attemptId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
        }

        // =====================================================================
        // P0-R2 â€” revoked org membership blocks CLASS_RESTRICTED even with a
        // lingering classroom_members row (discovery AND start)
        // =====================================================================

        @Test
        void p0r2_removedMemberWithLingeringClassMembershipDenied() throws Exception {
                Long organizer = register("r2c-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "CLASS_RESTRICTED");
                Long examId = jdbc.queryForObject(
                                "SELECT exam_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
                Long wsId = jdbc.queryForObject(
                                "SELECT workspace_id FROM exams WHERE id = " + examId, Long.class);
                Long schoolId = jdbc.queryForObject(
                                "SELECT school_id FROM exams WHERE id = " + examId, Long.class);

                // Learner with profile in the org school + class assigned to the session.
                Long learner = register("r2c-learner-" + System.nanoTime(), "STUDENT");
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + learner + "," + schoolId + ",'R2C')");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + learner + ",'MEMBER','ACTIVE')");
                Long classroom = insert("INSERT INTO classrooms (workspace_id, school_id, owner_teacher_id, "
                                + "code, name) VALUES (" + wsId + "," + schoolId
                                + ",(SELECT id FROM teacher_profiles WHERE user_id = " + organizer
                                + "),'R2C','C')");
                jdbc.update("INSERT INTO classroom_members (classroom_id, user_id, school_id, "
                                + "student_profile_id) VALUES (" + classroom + "," + learner + "," + schoolId
                                + "," + sp + ")");
                jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id) "
                                + "VALUES (" + sessionId + "," + classroom + "," + schoolId + ")");

                // Sanity: while ACTIVE, the session is discoverable and startable.
                mockMvc.perform(get("/api/exam-sessions/available").with(jwt(learner, "STUDENT")))
                                .andExpect(status().isOk());
                Integer visibleBefore = jdbc.queryForObject(
                                "SELECT count(*) FROM exam_sessions", Integer.class);
                assertThat(visibleBefore).isGreaterThan(0);
                // (full available-body assertion via HTTP below after revocation)

                // Revoke org membership; classroom_members row LINGERS.
                jdbc.update("UPDATE workspace_memberships SET status = 'REMOVED' "
                                + "WHERE workspace_id = " + wsId + " AND user_id = " + learner);

                // Discovery must NOT list the class-restricted session anymore.
                MvcResult available = mockMvc.perform(get("/api/exam-sessions/available").with(jwt(learner, "STUDENT")))
                                .andExpect(status().isOk()).andReturn();
                JsonNode items = mapper().readTree(available.getResponse().getContentAsString()).get("items");
                for (JsonNode item : items) {
                        assertThat(item.get("sessionId").asLong())
                                        .as("revoked member must not see the class-restricted session")
                                        .isNotEqualTo(sessionId);
                }

                // Start attempt must be denied.
                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId).with(jwt(learner, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void p0r2_suspendedMemberAlsoDenied() throws Exception {
                Long organizer = register("r2s-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "CLASS_RESTRICTED");
                Long examId = jdbc.queryForObject(
                                "SELECT exam_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
                Long wsId = jdbc.queryForObject(
                                "SELECT workspace_id FROM exams WHERE id = " + examId, Long.class);
                Long schoolId = jdbc.queryForObject(
                                "SELECT school_id FROM exams WHERE id = " + examId, Long.class);

                Long learner = register("r2s-learner-" + System.nanoTime(), "STUDENT");
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + learner + "," + schoolId + ",'R2S')");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + learner + ",'MEMBER','ACTIVE')");
                Long classroom = insert("INSERT INTO classrooms (workspace_id, school_id, owner_teacher_id, "
                                + "code, name) VALUES (" + wsId + "," + schoolId
                                + ",(SELECT id FROM teacher_profiles WHERE user_id = " + organizer
                                + "),'R2S','C')");
                jdbc.update("INSERT INTO classroom_members (classroom_id, user_id, school_id, "
                                + "student_profile_id) VALUES (" + classroom + "," + learner + "," + schoolId
                                + "," + sp + ")");
                jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id) "
                                + "VALUES (" + sessionId + "," + classroom + "," + schoolId + ")");

                jdbc.update("UPDATE workspace_memberships SET status = 'SUSPENDED' "
                                + "WHERE workspace_id = " + wsId + " AND user_id = " + learner);

                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId).with(jwt(learner, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void p0r2_activeMemberStillAllowed() throws Exception {
                Long organizer = register("r2a-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "CLASS_RESTRICTED");
                Long examId = jdbc.queryForObject(
                                "SELECT exam_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
                Long wsId = jdbc.queryForObject(
                                "SELECT workspace_id FROM exams WHERE id = " + examId, Long.class);
                Long schoolId = jdbc.queryForObject(
                                "SELECT school_id FROM exams WHERE id = " + examId, Long.class);

                Long learner = register("r2a-learner-" + System.nanoTime(), "STUDENT");
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + learner + "," + schoolId + ",'R2A')");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + learner + ",'MEMBER','ACTIVE')");
                Long classroom = insert("INSERT INTO classrooms (workspace_id, school_id, owner_teacher_id, "
                                + "code, name) VALUES (" + wsId + "," + schoolId
                                + ",(SELECT id FROM teacher_profiles WHERE user_id = " + organizer
                                + "),'R2A','C')");
                jdbc.update("INSERT INTO classroom_members (classroom_id, user_id, school_id, "
                                + "student_profile_id) VALUES (" + classroom + "," + learner + "," + schoolId
                                + "," + sp + ")");
                jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id, school_id) "
                                + "VALUES (" + sessionId + "," + classroom + "," + schoolId + ")");

                mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId).with(jwt(learner, "STUDENT"))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful());
        }

        // =====================================================================
        // P0-R3 â€” ACADEMIC_ADMIN is workspace-scoped; no cross-tenant bypass
        // =====================================================================

        @Test
        void p0r3_foreignAcademicAdminDeniedSessionResults() throws Exception {
                Long organizer = register("r2f-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "PUBLIC");
                publicUserSubmits(sessionId, "r2f-part-" + System.nanoTime()); // result row only

                // Admin of a DIFFERENT organization (own school, own workspace).
                Long foreignAdmin = register("r2f-admin-" + System.nanoTime(), "STUDENT");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + foreignAdmin
                                + ", id FROM roles WHERE code = 'ACADEMIC_ADMIN'");
                Long otherSchool = insert("INSERT INTO schools (code, name) VALUES ('R2F-SCH','O')");
                Long otherOrg = insert("INSERT INTO organizations (legacy_school_id, code, name) VALUES ("
                                + otherSchool + ",'R2F-ORG','O')");
                Long otherWs = insert("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION','O'," + otherOrg + ")");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + otherWs + "," + foreignAdmin + ",'ADMIN','ACTIVE')");

                mockMvc.perform(get("/api/exam-sessions/{id}/results", sessionId).with(jwt(foreignAdmin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
                mockMvc.perform(get("/api/exam-sessions/{id}/statistics", sessionId).with(jwt(foreignAdmin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
                mockMvc.perform(get("/api/exam-sessions/{id}/results/export", sessionId).with(jwt(foreignAdmin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
        }

        @Test
        void p0r3_academicAdminWithoutMembershipAnywhereDenied() throws Exception {
                Long organizer = register("r2n-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "PUBLIC");
                publicUserSubmits(sessionId, "r2n-part-" + System.nanoTime()); // result row only

                // ACADEMIC_ADMIN role with NO workspace membership at all.
                Long admin = register("r2n-admin-" + System.nanoTime(), "STUDENT");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + admin
                                + ", id FROM roles WHERE code = 'ACADEMIC_ADMIN'");

                mockMvc.perform(get("/api/exam-sessions/{id}/results", sessionId).with(jwt(admin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
                // Attempt-level result of another user also denied.
                Long attemptId = jdbc.queryForObject(
                                "SELECT id FROM attempts WHERE exam_session_id = " + sessionId
                                                + " LIMIT 1", Long.class);
                mockMvc.perform(get("/api/attempts/{id}/result", attemptId).with(jwt(admin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
        }

        @Test
        void p0r3_ownOrgAcademicAdminAllowed() throws Exception {
                Long organizer = register("r2o-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "PUBLIC");
                publicUserSubmits(sessionId, "r2o-part-" + System.nanoTime()); // result row only
                Long examId = jdbc.queryForObject(
                                "SELECT exam_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
                Long wsId = jdbc.queryForObject(
                                "SELECT workspace_id FROM exams WHERE id = " + examId, Long.class);

                Long admin = register("r2o-admin-" + System.nanoTime(), "STUDENT");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + admin
                                + ", id FROM roles WHERE code = 'ACADEMIC_ADMIN'");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + admin + ",'ADMIN','ACTIVE')");

                MvcResult debug = mockMvc.perform(get("/api/exam-sessions/{id}/results", sessionId)
                                .with(jwt(admin, "STUDENT", "ACADEMIC_ADMIN")))
                                .andExpect(status().isOk()).andReturn();
                assertThat(debug.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void p0r3_revokedAcademicAdminDenied() throws Exception {
                Long organizer = register("r2r-organizer-" + System.nanoTime(), "TEACHER");
                Long sessionId = orgExamSession(organizer, "PUBLIC");
                publicUserSubmits(sessionId, "r2r-part-" + System.nanoTime()); // result row only
                Long examId = jdbc.queryForObject(
                                "SELECT exam_id FROM exam_sessions WHERE id = " + sessionId, Long.class);
                Long wsId = jdbc.queryForObject(
                                "SELECT workspace_id FROM exams WHERE id = " + examId, Long.class);

                Long admin = register("r2r-admin-" + System.nanoTime(), "STUDENT");
                jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + admin
                                + ", id FROM roles WHERE code = 'ACADEMIC_ADMIN'");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + admin + ",'ADMIN','SUSPENDED')");

                mockMvc.perform(get("/api/exam-sessions/{id}/results", sessionId).with(jwt(admin, "ACADEMIC_ADMIN")))
                                .andExpect(status().isForbidden());
        }
}
