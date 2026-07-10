package com.hhtuann.backend.attempt;

import com.hhtuann.backend.attempt.application.AttemptService;
import com.hhtuann.backend.attempt.dto.StartAttemptRequest;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
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
        long school = insert("INSERT INTO schools (code, name) VALUES ('HS','HS')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + studentUserId + "," + school + ",'TC')");
        long sp = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentUserId + "," + school + ",'SC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + "," + tp + ",'B','B')");
        long q = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + studentUserId + ")");
        long qv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','Q',1,'{}'::jsonb," + studentUserId + ")");
        String numericKey = "'{\"expectedAnswer\":\"2.50\",\"requiredInputLength\":4,\"roundingInstruction\":\"two decimals\"}'::jsonb";
        long qn = insert("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'QN'," + studentUserId + ")");
        long qnv = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, answer_key, created_by) VALUES (" + qn + ",1,'NUMERIC_FILL','N',1,'{}'::jsonb," + numericKey + "," + studentUserId + ")");
        long exam = insert("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + "," + tp + ",'E','E')");
        long ver = insert("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',2,now()," + studentUserId + ")");
        long sec = insert("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long eq = insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','Q',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        insert("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata, answer_key) VALUES (" + ver + "," + sec + "," + qn + "," + qnv + ",'QCN','NUMERIC_FILL','N',1,1,'{}'::jsonb," + numericKey + ")");
        long session = insert("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + "," + tp + ",'S','S','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + studentUserId + ",'" + now.minusSeconds(3600) + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES (" + school + "," + session + "," + sp + "," + studentUserId + ")");
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
    void detailReturns403MissingStudentRole() throws Exception {
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
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
    void detailReturns404MissingProfile() throws Exception {
        long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp','mp@t.com','h','MP')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(u)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
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
        // Give 'other' an ACTIVE profile in the SAME school so auth passes; the attempt belongs to the
        // caller → ownership check must return 404 ATTEMPT_NOT_FOUND (anti-enumeration), not 403.
        insert("INSERT INTO student_profiles (user_id, school_id, student_code) "
                + "VALUES (" + other + ",(SELECT school_id FROM attempts WHERE id=" + attemptId + "),'OS')");
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
                .andExpect(jsonPath("$.studentProfileId").doesNotExist())
                .andExpect(jsonPath("$.schoolId").doesNotExist())
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
    void myReturns403MissingStudentRole() throws Exception {
        long teacherRoleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='TEACHER'", Long.class);
        jdbc.update("UPDATE user_roles SET role_id=" + teacherRoleId + " WHERE user_id=" + studentUserId);
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void myReturns403RevokedAttemptRead() throws Exception {
        revoke("ATTEMPT_READ");
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    @Test
    void myReturns404MissingProfile() throws Exception {
        long u = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('mp2','mp2@t.com','h','MP2')");
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + u + "," + roleId + ")");
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(u)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTEMPT_STUDENT_PROFILE_NOT_FOUND"));
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
        // Other student's attempt must not appear in this caller's history.
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('fo','fo@t.com','h','FO')");
        long sp2 = insert("INSERT INTO student_profiles (user_id, school_id, student_code) SELECT " + other + ", school_id, 'FO' FROM student_profiles WHERE user_id=" + studentUserId);
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='STUDENT'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + other + "," + roleId + ")");
        long session = jdbc.queryForObject("SELECT exam_session_id FROM attempts WHERE id=" + attemptId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM attempts WHERE id=" + attemptId, Long.class);
        long school = jdbc.queryForObject("SELECT school_id FROM attempts WHERE id=" + attemptId, Long.class);
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) "
                + "VALUES (" + school + "," + session + "," + sp2 + "," + studentUserId + ") ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO attempts (school_id, exam_session_id, student_profile_id, exam_version_id, attempt_number, "
                + "status, started_at, deadline_at) VALUES (" + school + "," + session + "," + sp2 + "," + ver + ",1,'IN_PROGRESS',now(),now()+interval '1 hour')");
        mockMvc.perform(get("/api/attempts/my").with(jwt().jwt(j -> j.subject(String.valueOf(studentUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].attemptId").value(attemptId))
                // No leak fields:
                .andExpect(jsonPath("$.items[*].answerPayload").doesNotExist())
                .andExpect(jsonPath("$.items[*].questions").doesNotExist())
                .andExpect(jsonPath("$.items[*].score").doesNotExist())
                .andExpect(jsonPath("$.items[*].grade").doesNotExist())
                .andExpect(jsonPath("$.items[*].studentProfileId").doesNotExist());
    }

    private void revoke(String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id=(SELECT id FROM roles WHERE code='STUDENT') "
                + "AND permission_id=(SELECT id FROM permissions WHERE code='" + permission + "')");
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
