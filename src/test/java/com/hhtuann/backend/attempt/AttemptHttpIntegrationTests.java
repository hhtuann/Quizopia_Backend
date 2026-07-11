package com.hhtuann.backend.attempt;

import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;

/**
 * MockMvc HTTP integration tests for the two A3.2-1 endpoints. Uses Spring
 * Security's
 * jwt() post-processor (no real login). Each error test asserts exact HTTP
 * status +
 * exact code in JSON body. No answerKey/isCorrect/explanation/score in
 * responses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ PostgresTestContainerConfiguration.class, TestClockConfig.class })
@Transactional
class AttemptHttpIntegrationTests {

        @Autowired
        private MockMvc mockMvc;
        @Autowired
        private JdbcTemplate jdbc;
        @Autowired
        private MutableClock clock;
        @Autowired
        private AttemptService attemptService;

        private long studentUserId;
        private long sessionId;
        private Instant baseTime = Instant.parse("2026-07-03T08:00:00Z");

        @BeforeEach
        void setUp() {
                clock.setInstant(baseTime);
                studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) "
                                + "VALUES ('ht','ht@t.com','h','HT')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
                long school = insert("INSERT INTO schools (code, name) VALUES ('HS','HTTP School')");
                long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
                long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school
                                + "," + gl + ",'M','Math')");
                long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES ("
                                + studentUserId + "," + school + ",'TC')");
                long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES ("
                                + studentUserId + "," + school + ",'SC')");
                long bank = insert(
                                "INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES ("
                                                + school + "," + subj + "," + tp + ",'B','Bank')");
                long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q',"
                                + studentUserId + ")");
                long qv = insert(
                                "INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES ("
                                                + q + ",1,'SINGLE_CHOICE','Q1',1,'{}'::jsonb," + studentUserId + ")");
                long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES ("
                                + school + "," + subj + "," + tp + ",'E','Exam')");
                long ver = insert(
                                "INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES ("
                                                + school + "," + exam + ",1,'PUBLISHED',1,now()," + studentUserId
                                                + ")");
                long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver
                                + ",'S',0)");
                long eq = insert(
                                "INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES ("
                                                + ver + "," + sec + "," + q + "," + qv
                                                + ",'QC','SINGLE_CHOICE','Q1',1,0,'{}'::jsonb)");
                jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES ("
                                + eq + ",'A','A',false,0),(" + eq + ",'B','B',false,1),(" + eq + ",'C','C',true,2),("
                                + eq + ",'D','D',false,3)");
                sessionId = insert(
                                "INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                                                + school + "," + ver + "," + tp + ",'S','Sess','OPEN','"
                                                + baseTime.minusSeconds(3600) + "','" + baseTime.plusSeconds(7200)
                                                + "',1," + studentUserId + ",'" + baseTime.minusSeconds(3600) + "')");
                insert("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                                + school + "," + sessionId + "," + sp + "," + studentUserId + ")");
        }

        private String jwt() {
                return studentUserId + "";
        }

        // === GET /api/exam-sessions/available ===

        @Test
        void availableReturns200() throws Exception {
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items[0].sessionId").value(sessionId));
        }

        @Test
        void availableReturns401Unauthenticated() throws Exception {
                mockMvc.perform(get("/api/exam-sessions/available"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void availableReturns403MissingStudentRole() throws Exception {
                long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void availableNoAnswerLeak() throws Exception {
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items[0].answerKey").doesNotExist())
                                .andExpect(jsonPath("$.items[0].isCorrect").doesNotExist());
        }

        // === POST /api/exam-sessions/{sessionId}/attempts ===

        @Test
        void startReturns201NewAttempt() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.attemptId").isNumber())
                                .andExpect(jsonPath("$.resumed").value(false))
                                .andExpect(jsonPath("$.questions[0].options").isArray());
        }

        @Test
        void startReturns200Resume() throws Exception {
                // First call creates.
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isCreated());
                // Second call resumes.
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.resumed").value(true));
        }

        @Test
        void startReturns400MalformedUUID() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"clientInstanceId\":\"not-a-uuid\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void startReturns400MalformedJSON() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{invalid"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_VALIDATION_ERROR"));
        }

        @Test
        void startReturns401Unauthenticated() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        void startReturns403WrongRole() throws Exception {
                long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
                jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startReturns404SessionNotFound() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/999999/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("EXAM_SESSION_NOT_FOUND"));
        }

        @Test
        void startReturns409NotOpen() throws Exception {
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_SESSION_NOT_OPEN"));
        }

        @Test
        void startReturns409OutsideWindow() throws Exception {
                jdbc.update("UPDATE exam_sessions SET starts_at='" + baseTime.plusSeconds(3600) + "' WHERE id="
                                + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_OUTSIDE_WINDOW"));
        }

        @Test
        void startNoAnswerLeak() throws Exception {
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.questions[0].answerKey").doesNotExist())
                                .andExpect(jsonPath("$.questions[0].options[0].isCorrect").doesNotExist())
                                .andExpect(jsonPath("$.questions[0].explanation").doesNotExist());
        }

        // === R4 Missing MockMvc cases ===

        @Test
        void availableReturns403InactiveProfile() throws Exception {
                jdbc.update("UPDATE student_profiles SET enrollment_status='INACTIVE'");
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void availableReturns403GraduatedProfile() throws Exception {
                jdbc.update("UPDATE student_profiles SET enrollment_status='GRADUATED'");
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void availableReturns403WithdrawnProfile() throws Exception {
                jdbc.update("UPDATE student_profiles SET enrollment_status='WITHDRAWN'");
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startReturns409DRAFT() throws Exception {
                jdbc.update("UPDATE exam_sessions SET status='DRAFT', opened_at=NULL WHERE id=" + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_SESSION_NOT_OPEN"));
        }

        @Test
        void startAutoOpensSCHEDULEDInWindow() throws Exception {
                // Lazy-open: SCHEDULED within the time window → auto-opened → 200 (not 409).
                jdbc.update("UPDATE exam_sessions SET status='SCHEDULED', opened_at=NULL WHERE id=" + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isCreated());
        }

        @Test
        void startReturns409MaxReached() throws Exception {
                jdbc.update("UPDATE exam_sessions SET max_attempts=1 WHERE id=" + sessionId);
                // Create and submit attempt 1 to exhaust quota.
                Long attemptId = attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId();
                jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), submission_idempotency_key='MAXTEST' WHERE id="
                                + attemptId);
                // POST again → MAX_REACHED (no active attempt, but quota exhausted).
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_MAX_REACHED"));
        }

        @Test
        void startReturns409ExpiredActive() throws Exception {
                // Create an attempt, then advance clock past deadline.
                Long attemptId = attemptService.startAttempt(studentUserId, sessionId, new StartAttemptRequest(null))
                                .attemptId();
                Instant deadline = jdbc.queryForObject("SELECT deadline_at FROM attempts WHERE id=" + attemptId,
                                Instant.class);
                clock.setInstant(deadline.plusSeconds(60));
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_DEADLINE_EXCEEDED"));
        }

        @Test
        void startReturns409CANCELLED() throws Exception {
                jdbc.update("UPDATE exam_sessions SET status='CANCELLED', opened_at=NULL, closed_at=NULL WHERE id="
                                + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_SESSION_NOT_OPEN"));
        }

        @Test
        void availableReturns403RevokedPermission() throws Exception {
                // Delete the role_permission mapping for EXAM_SESSION_READ on STUDENT.
                jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                                + "AND permission_id=(SELECT id FROM permissions WHERE code='EXAM_SESSION_READ')");
                mockMvc.perform(get("/api/exam-sessions/available").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt()))))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void startReturns403RevokedPermission() throws Exception {
                jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                                + "AND permission_id=(SELECT id FROM permissions WHERE code='ATTEMPT_START')");
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void errorResponsesDoNotLeakInternalInfo() throws Exception {
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
                String body = mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain("constraint").doesNotContain("SQL")
                                .doesNotContain("org.hibernate").doesNotContain("org.postgresql");
        }

        @Test
        void startReturns409AfterEndsAt() throws Exception {
                jdbc.update("UPDATE exam_sessions SET ends_at='" + baseTime.minusSeconds(1) + "' WHERE id="
                                + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_OUTSIDE_WINDOW"));
        }

        @Test
        void startReturns409BeforeStartsAt() throws Exception {
                jdbc.update("UPDATE exam_sessions SET starts_at='" + baseTime.plusSeconds(3600) + "' WHERE id="
                                + sessionId);
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(
                                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                .jwt()
                                                .jwt(j -> j.subject(jwt())))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_OUTSIDE_WINDOW"));
        }

        // === R7: missing-profile / SYSTEM_ADMIN / hidden-status HTTP edges ===

        @Test
        void availableReturns404MissingStudentProfile() throws Exception {
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp2','mp2@t.com','h','MP2')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
                // STUDENT role + EXAM_SESSION_READ permission, but no StudentProfile → 404.
                mockMvc.perform(get("/api/exam-sessions/available").with(jwtWithSubject(u)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
        }

        @Test
        void availableReturns403SystemAdminWithoutStudentRole() throws Exception {
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sa','sa@t.com','h','SA')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='SYSTEM_ADMIN'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
                // SYSTEM_ADMIN has no STUDENT role → no bypass; deny-by-default → 403.
                mockMvc.perform(get("/api/exam-sessions/available").with(jwtWithSubject(u)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
        }

        @Test
        void availableHidesClosedCancelledDraftSessions() throws Exception {
                // CLOSED → hidden.
                jdbc.update("UPDATE exam_sessions SET status='CLOSED', closed_at=now() WHERE id=" + sessionId);
                mockMvc.perform(get("/api/exam-sessions/available").with(jwtWithSubject(studentUserId)))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
                // CANCELLED → hidden (nulled timestamps satisfy the state CHECK).
                jdbc.update("UPDATE exam_sessions SET status='CANCELLED', opened_at=NULL, closed_at=NULL WHERE id="
                                + sessionId);
                mockMvc.perform(get("/api/exam-sessions/available").with(jwtWithSubject(studentUserId)))
                                .andExpect(jsonPath("$.items").isEmpty());
                // DRAFT → hidden.
                jdbc.update("UPDATE exam_sessions SET status='DRAFT', opened_at=NULL WHERE id=" + sessionId);
                mockMvc.perform(get("/api/exam-sessions/available").with(jwtWithSubject(studentUserId)))
                                .andExpect(jsonPath("$.items").isEmpty());
        }

        @Test
        void startReturns404MissingStudentProfile() throws Exception {
                long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sp2','sp2@t.com','h','SP2')");
                long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
                jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
                mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts").with(jwtWithSubject(u))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
        }

        private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtWithSubject(
                        long userId) {
                return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(j -> j.subject(String.valueOf(userId)));
        }

        private long insert(String sql) {
                return jdbc.queryForObject(sql + " RETURNING id", Long.class);
        }
}
