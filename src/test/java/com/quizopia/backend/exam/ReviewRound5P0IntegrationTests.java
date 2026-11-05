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
 * Issue #4 review round 5 reproduction (P0-R9) — GET participant management
 * must be user-based: profile-less blocked participants expose userId +
 * displayName with studentProfileId null; legacy bulk-add duplicate
 * classification covers user-based rows without 500s or status flips.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ReviewRound5P0IntegrationTests {

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

        /** Org PUBLIC session fixture. Returns [sessionId, wsId, schoolId]. */
        private Long[] orgPublicSession(Long organizer) {
                return orgPublicSession(organizer, "OPEN");
        }

        private Long[] orgPublicSession(Long organizer, String sessionStatus) {
                Long schoolId = insert("INSERT INTO schools (code, name) VALUES ('R5-SCH-" + System.nanoTime()
                                + "','S')");
                Long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId
                                + ",'GL','G')");
                Long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES ("
                                + schoolId + "," + gl + ",'SUB','S')");
                Long orgWs = insert("INSERT INTO organizations (legacy_school_id, code, name) VALUES ("
                                + schoolId + ",'R5-ORG','O')");
                Long wsId = insert("INSERT INTO workspaces (workspace_type, name, organization_id) "
                                + "VALUES ('ORGANIZATION','O'," + orgWs + ")");
                jdbc.update("INSERT INTO workspace_memberships (workspace_id, user_id, role, status) "
                                + "VALUES (" + wsId + "," + organizer + ",'EDITOR','ACTIVE')");
                Long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES ("
                                + organizer + "," + schoolId + ",'TC')");
                Long bank = insert("INSERT INTO question_banks (workspace_id, created_by_user_id, school_id, "
                                + "subject_id, owner_teacher_id, code, name) VALUES (" + wsId + "," + organizer
                                + "," + schoolId + "," + subjectId + "," + tp + ",'B','B')");
                Long q = insert("INSERT INTO questions (question_bank_id, code, status, created_by) VALUES ("
                                + bank + ",'q','ACTIVE'," + organizer + ")");
                Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                                + "content, difficulty, default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','q?','MEDIUM',1,'{}'::jsonb," + organizer + ")");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + v + ",'" + o[0] + "','o'," + o[1]
                                        + "," + o[2] + ")");
                }
                Long examId = insert("INSERT INTO exams (workspace_id, created_by_user_id, school_id, subject_id, "
                                + "owner_teacher_id, code, title, status, published_at, total_points, "
                                + "instructions, duration_minutes, tf_matrix_scoring) VALUES (" + wsId + ","
                                + organizer + "," + schoolId + "," + subjectId + "," + tp + ",'R5E','E',"
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
                                + ",'R5SES','S','" + sessionStatus + "','" + STARTS + "','" + ENDS + "'," + organizer
                                + ",'PUBLIC',5,60," + ("OPEN".equals(sessionStatus) ? "now()" : "NULL") + ")");
                return new Long[] { sessionId, wsId, schoolId };
        }

        private Long startAttempt(Long sessionId, Long userId, String role) throws Exception {
                MvcResult start = mockMvc.perform(post("/api/exam-sessions/{id}/attempts", sessionId)
                                .with(jwt(userId, role))
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andExpect(status().is2xxSuccessful())
                                .andReturn();
                return mapper().readTree(start.getResponse().getContentAsString()).get("attemptId").asLong();
        }

        private void blockByUser(Long sessionId, Long organizer, Long targetUserId) throws Exception {
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/by-user/{uid}/block",
                                sessionId, targetUserId).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());
        }

        // =====================================================================
        // R9-1 — GET /participants is user-based for profile-less rows
        // =====================================================================

        @Test
        void r9_listReturnsProfileLessBlockedParticipantWithUserIdAndDisplayName() throws Exception {
                Long organizer = register("r9-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                Long publicUser = register("r9-pub-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, publicUser, "TEACHER");
                blockByUser(sessionId, organizer, publicUser);

                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].userId").value(publicUser.intValue()))
                                .andExpect(jsonPath("$.items[0].studentProfileId")
                                                .value(org.hamcrest.Matchers.nullValue()))
                                .andExpect(jsonPath("$.items[0].displayName").isNotEmpty())
                                .andExpect(jsonPath("$.items[0].status").value("BLOCKED"));
        }

        @Test
        void r9_listStatusFilterAndPagination() throws Exception {
                Long organizer = register("r9b-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];

                // 3 public users: 2 blocked (participant rows materialize on
                // block), 1 blocked-then-unblocked (row stays ELIGIBLE).
                Long u1 = register("r9b-u1-" + System.nanoTime(), "TEACHER");
                Long u2 = register("r9b-u2-" + System.nanoTime(), "TEACHER");
                Long u3 = register("r9b-u3-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, u1, "TEACHER");
                startAttempt(sessionId, u2, "TEACHER");
                startAttempt(sessionId, u3, "TEACHER");
                blockByUser(sessionId, organizer, u1);
                blockByUser(sessionId, organizer, u2);
                blockByUser(sessionId, organizer, u3);
                mockMvc.perform(post("/api/exam-sessions/{id}/participants/by-user/{uid}/unblock",
                                sessionId, u3).with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk());

                // status=BLOCKED filter → exactly the 2 blocked.
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .param("status", "BLOCKED")
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(2));

                // pagination size=2 page=0 → 2 items, totalElements=3.
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .param("size", "2").param("page", "0")
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(2))
                                .andExpect(jsonPath("$.totalElements").value(3));
        }

        @Test
        void r9_legacyRowKeepsStudentCodeAndDisplayName() throws Exception {
                Long organizer = register("r9c-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];
                Long schoolId = fixture[2];

                Long student = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('r9c-stu-" + System.nanoTime() + "','r9c@t.com','h','R9C Student')");
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + student + "," + schoolId + ",'R9C')");
                jdbc.update("INSERT INTO exam_session_participants (user_id, school_id, exam_session_id, "
                                + "student_profile_id, added_by) VALUES (" + student + "," + schoolId + ","
                                + sessionId + "," + sp + "," + organizer + ")");

                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].userId").value(student.intValue()))
                                .andExpect(jsonPath("$.items[0].studentProfileId").value(sp.intValue()))
                                .andExpect(jsonPath("$.items[0].studentCode").value("R9C"))
                                .andExpect(jsonPath("$.items[0].displayName").value("R9C Student"))
                                .andExpect(jsonPath("$.items[0].status").value("ELIGIBLE"));
        }

        @Test
        void r9_personalWorkspaceParticipantListing() throws Exception {
                Long owner = register("r9d-owner-" + System.nanoTime(), "TEACHER"); // no profile
                Long personalWs = jdbc.queryForObject(
                                "SELECT id FROM workspaces WHERE owner_user_id = " + owner
                                                + " AND workspace_type = 'PERSONAL'", Long.class);
                Long bank = insert("INSERT INTO question_banks (workspace_id, created_by_user_id, code, name) "
                                + "VALUES (" + personalWs + "," + owner + ",'PB','PB')");
                Long q = insert("INSERT INTO questions (question_bank_id, code, status, created_by) VALUES ("
                                + bank + ",'q','ACTIVE'," + owner + ")");
                Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, "
                                + "content, difficulty, default_points, metadata, created_by) VALUES (" + q
                                + ",1,'SINGLE_CHOICE','q?','MEDIUM',1,'{}'::jsonb," + owner + ")");
                for (Object[] o : new Object[][] { { "A", true, 0 }, { "B", false, 1 }, { "C", false, 2 },
                                { "D", false, 3 } }) {
                        insert("INSERT INTO question_options (question_version_id, option_key, content, "
                                        + "is_correct, position) VALUES (" + v + ",'" + o[0] + "','o'," + o[1]
                                        + "," + o[2] + ")");
                }
                Long examId = insert("INSERT INTO exams (workspace_id, created_by_user_id, code, title, status, "
                                + "published_at, total_points, instructions, duration_minutes, tf_matrix_scoring) "
                                + "VALUES (" + personalWs + "," + owner + ",'PE','PE','PUBLISHED',now(),1,'i',30,"
                                + "'{\"0\":0,\"1\":10,\"2\":25,\"3\":50,\"4\":100}'::jsonb)");
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
                                + "VALUES (" + examId + ",'PSES','S','OPEN','" + STARTS + "','" + ENDS + "',"
                                + owner + ",'PUBLIC',5,60,now())");

                Long publicUser = register("r9d-pub-" + System.nanoTime(), "STUDENT");
                startAttempt(sessionId, publicUser, "STUDENT");
                blockByUser(sessionId, owner, publicUser);

                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(owner, "TEACHER")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andExpect(jsonPath("$.items[0].userId").value(publicUser.intValue()))
                                .andExpect(jsonPath("$.items[0].status").value("BLOCKED"));
        }

        // =====================================================================
        // R9-2 — legacy bulk-add duplicate classification (user-primary)
        // =====================================================================

        @Test
        void r9_legacyBulkAddDuplicateAfterUserBasedRowNo500NoUnblock() throws Exception {
                Long organizer = register("r9e-org-" + System.nanoTime(), "TEACHER");
                // SCHEDULED: legacy bulk-add only allows DRAFT/SCHEDULED;
                // startAttempt lazy-opens it inside the window.
                Long[] fixture = orgPublicSession(organizer, "SCHEDULED");
                Long sessionId = fixture[0];
                Long schoolId = fixture[2];

                // 1) Public user (no profile) has a user-based BLOCKED row —
                // inserted directly (startAttempt would lazy-open the
                // SCHEDULED session and the legacy add below would 409; the
                // duplicate-classification path under test is identical).
                Long publicUser = register("r9e-pub-" + System.nanoTime(), "TEACHER");
                jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by, "
                                + "status, blocked_at) VALUES (" + publicUser + "," + sessionId + ","
                                + organizer + ",'BLOCKED',now())");

                // 2) The SAME user later acquires a StudentProfile in the school.
                Long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + publicUser + "," + schoolId + ",'R9E')");

                // 3) Legacy bulk-add with that profile: must classify as DUPLICATE
                //    (business result), NOT a 500 from uk_..._session_user.
                MvcResult res = mockMvc.perform(post("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentProfileIds\":[" + sp + "]}"))
                                .andExpect(status().isOk())
                                .andReturn();
                JsonNode body = mapper().readTree(res.getResponse().getContentAsString());
                assertThat(body.get("added").asInt()).isZero();
                assertThat(body.get("duplicated").toString()).contains(sp.toString());

                // 4) The BLOCKED state is untouched — no silent unblock.
                String status = jdbc.queryForObject(
                                "SELECT status FROM exam_session_participants WHERE exam_session_id = "
                                                + sessionId + " AND user_id = " + publicUser, String.class);
                assertThat(status).isEqualTo("BLOCKED");
        }

        @Test
        void r9_revokedAndForeignStillDenied() throws Exception {
                Long organizer = register("r9f-org-" + System.nanoTime(), "TEACHER");
                Long[] fixture = orgPublicSession(organizer);
                Long sessionId = fixture[0];
                Long wsId = fixture[1];

                Long publicUser = register("r9f-pub-" + System.nanoTime(), "TEACHER");
                startAttempt(sessionId, publicUser, "TEACHER");

                jdbc.update("UPDATE workspace_memberships SET status = 'REMOVED' "
                                + "WHERE workspace_id = " + wsId + " AND user_id = " + organizer);
                // Production revocation happens in another session; detach cached state.
                emClear();

                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(organizer, "TEACHER")))
                                .andExpect(status().isForbidden());
                Long foreign = register("r9f-foreign-" + System.nanoTime(), "TEACHER");
                mockMvc.perform(get("/api/exam-sessions/{id}/participants", sessionId)
                                .with(jwt(foreign, "TEACHER")))
                                .andExpect(status().isForbidden());
        }

        @jakarta.persistence.PersistenceContext
        private jakarta.persistence.EntityManager em;

        private void emClear() {
                em.clear();
        }
}
