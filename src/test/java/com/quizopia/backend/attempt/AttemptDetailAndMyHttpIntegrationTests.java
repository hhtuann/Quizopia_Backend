package com.quizopia.backend.attempt;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP tests for {@code GET /api/attempts/{id}} and {@code GET /api/attempts/my} (A3.2-2).
 * Each error test asserts the exact HTTP status and {@code $.code}; no internal leak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@Transactional
class AttemptDetailAndMyHttpIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttemptService attemptService;
    @Autowired private MutableClock clock;

    private long studentUserId;
    private long attemptId;
    private long aqSingleId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-03T08:00:00Z");
        clock.setInstant(now);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('h','h@t.com','h','H')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + studentUserId + "," + roleId + ")");
        long gl = insert("INSERT INTO grade_levels (code, name) VALUES ('GL" + java.util.UUID.randomUUID().toString().substring(0, 6) + "','G')");
        long subj = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'M','M')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + studentUserId + "," + subj + ",'B','B')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q',1,'{}'::jsonb," + studentUserId + ")");
        String numericKey = "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long qn = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN'," + studentUserId + ")");
        long qnv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + qn + ",1,'NUMERIC_FILL','N',1,'{}'::jsonb," + numericKey + "," + studentUserId + ")");
        long exam = insert("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, total_points) VALUES (" + studentUserId + "," + subj + ",'E','E','PUBLISHED',now(),2)");
        long sec = insert("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + exam + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + exam + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        insert("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + exam + "," + sec + "," + qn + "," + qnv + ",'QCN','NUMERIC_FILL','N',1,1,'{}'::jsonb," + numericKey + ")");
        long session = insert("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at, duration_minutes) VALUES ("
                + exam + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "',60)");
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES (" + studentUserId + "," + session + "," + studentUserId + ")");
        attemptId = attemptService.startAttempt(studentUserId, session, new StartAttemptRequest(null)).attemptId();
        aqSingleId = jdbc.queryForObject("SELECT id FROM attempt_questions WHERE attempt_id=" + attemptId + " AND question_type='SINGLE_CHOICE'", Long.class);
    }

    // === GET /api/attempts/{attemptId} ===

    @Test
    void detailReturns200() throws Exception {
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId))
                .andExpect(jsonPath("$.questions[0].displayOrder").value(0))
                .andExpect(jsonPath("$.questions[0].options[0].optionKey").exists());
    }

    @Test
    void detailReturns401Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/attempts/" + attemptId)).andExpect(status().isUnauthorized());
    }

    @Test
    void detailAllowsTeacherRole() throws Exception {
        // Dual capability (V16): TEACHER carries ATTEMPT_READ + ATTEMPT_ANSWER_READ,
        // and the attempt owner keeps detail access after switching to the TEACHER role.
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId));
    }

    @Test
    void detailReturns403RevokedAttemptRead() throws Exception {
        revoke("ATTEMPT_READ");
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void detailReturns403RevokedAnswerRead() throws Exception {
        revoke("ATTEMPT_ANSWER_READ");
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void detailReturns404NonOwnerForeignAttempt() throws Exception {
        long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp','mp@t.com','h','MP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
        // The caller passes the permission gate but does not own the attempt →
        // 404 anti-enumeration (issue #4 P0-1).
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(u)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailReturns404MissingAttempt() throws Exception {
        mockMvc.perform(get("/api/attempts/999999").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailReturns404ForeignAttempt() throws Exception {
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o','o@t.com','h','O')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        // The attempt belongs to another user → the ownership check must return
        // 404 ATTEMPT_NOT_FOUND (anti-enumeration), not 403.
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(other)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
    }

    @Test
    void detailClearAnswerKeepsSavedAnswerObject() throws Exception {
        jdbc.update("INSERT INTO attempt_answers (attempt_id, attempt_question_id, answer_payload, sequence_number, saved_at) "
                + "VALUES (" + attemptId + "," + aqSingleId + ",NULL,7,now())");
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].savedAnswer.sequenceNumber").value(7))
                .andExpect(jsonPath("$.questions[0].savedAnswer.answerPayload").isEmpty());
    }

    @Test
    void detailNumericHasNoAnswerKeyLeak() throws Exception {
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                // No leak fields on any question/option (roundingInstruction now lives in the content):
                .andExpect(jsonPath("$.questions[*].answerKey").doesNotExist())
                .andExpect(jsonPath("$.questions[*].expectedAnswer").doesNotExist())
                .andExpect(jsonPath("$.questions[*].isCorrect").doesNotExist())
                .andExpect(jsonPath("$.questions[*].explanation").doesNotExist())
                .andExpect(jsonPath("$.questions[*].score").doesNotExist())
                .andExpect(jsonPath("$.questions[*].grade").doesNotExist())
                .andExpect(jsonPath("$.questions[*].options[*].isCorrect").doesNotExist())
                // No identity/internal fields on the attempt envelope:
                .andExpect(jsonPath("$.clientInstanceId").doesNotExist())
                .andExpect(jsonPath("$.submissionIdempotencyKey").doesNotExist());
    }

    // === GET /api/attempts/my ===

    @Test
    void myReturns200() throws Exception {
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.sort").value("createdAt: DESC"))
                .andExpect(jsonPath("$.items[0].attemptId").value(attemptId))
                .andExpect(jsonPath("$.items[0].sessionCode").value("S"))
                .andExpect(jsonPath("$.items[0].sessionTitle").value("S"));
    }

    @Test
    void myReturns401Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/attempts/my")).andExpect(status().isUnauthorized());
    }

    @Test
    void myAllowsTeacherRole() throws Exception {
        // Dual capability (V16): TEACHER carries ATTEMPT_READ — history is
        // permission-gated, not role-gated.
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].attemptId").value(attemptId));
    }

    @Test
    void myReturns403RevokedAttemptRead() throws Exception {
        revoke("ATTEMPT_READ");
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void myAllowsUserWithoutAttempts() throws Exception {
        long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp2','mp2@t.com','h','MP2')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
        // Any user sees their own (empty) history; no participant row required.
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(u)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void myPaginationResponseCorrect() throws Exception {
        mockMvc.perform(get("/api/attempts/my?page=0&size=5").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void myForeignAttemptsNotPresent() throws Exception {
        // Another user's attempt must not appear in this caller's history.
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('fo','fo@t.com','h','FO')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        long session = jdbc.queryForObject("SELECT exam_session_id FROM attempts WHERE id=" + attemptId, Long.class);
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) "
                + "VALUES (" + other + "," + session + "," + studentUserId + ") ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, "
                + "status, started_at, deadline_at) VALUES (" + session + "," + other + ",1,'IN_PROGRESS',now(),now()+interval '1 hour')");
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].attemptId").value(attemptId))
                // No leak fields: answerPayload/questions/grade must not exist.
                // score/maxScore DO exist in the list DTO (as null) — the actual values come
                // from the detail endpoint, not the list.
                .andExpect(jsonPath("$.items[*].answerPayload").doesNotExist())
                .andExpect(jsonPath("$.items[*].questions").doesNotExist())
                .andExpect(jsonPath("$.items[*].score").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.items[*].grade").doesNotExist());
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
