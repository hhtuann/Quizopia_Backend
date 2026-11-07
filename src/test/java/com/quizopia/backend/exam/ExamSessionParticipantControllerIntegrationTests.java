package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionParticipantControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long teacherUserId;
    private Long sessionId;
    private Long studentUserId;

    @BeforeEach
    void setUp() throws Exception {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('pc2','pc2@t','h','PC2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        Long subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        Long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subjectId + ",'QB','Bank')");
        Long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long examId = createExamAndPublish(subjectId);
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionId = createSession(examId, starts, ends);
        studentUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s1','s1@t','h','Student1')");
    }

    @Test
    void listParticipantsReturns200() throws Exception {
        // The bulk add-participants endpoint was removed with the school model;
        // participant rows are staged via direct SQL (user-based identity).
        jdbc.update("INSERT INTO exam_session_participants (exam_session_id, user_id, status, added_by, version) "
                + "VALUES (?,?,?,?,0)", sessionId, studentUserId, "ELIGIBLE", teacherUserId);
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(studentUserId))
                .andExpect(jsonPath("$.items[0].displayName").value("Student1"))
                .andExpect(jsonPath("$.items[0].status").value("ELIGIBLE"))
                .andExpect(jsonPath("$.items[0].answerKey").doesNotExist());
    }

    @Test
    void blockUnblockReturns200() throws Exception {
        Long pid = insertParticipant();
        entityManager.clear();
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants/" + pid + "/block")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        entityManager.clear();
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants/" + pid + "/unblock")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ELIGIBLE"));
    }

    @Test
    void blockUnblockByUserReturns200() throws Exception {
        insertParticipant();
        entityManager.clear();
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants/by-user/" + studentUserId + "/block")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(studentUserId))
                .andExpect(jsonPath("$.status").value("BLOCKED"));
        entityManager.clear();
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants/by-user/" + studentUserId + "/unblock")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ELIGIBLE"));
    }

    @Test
    void listUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSortByStudentCodeReturns400() throws Exception {
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .param("sort", "studentCode,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXAM_VALIDATION_ERROR"));
    }

    @Test
    void listForeignOwnerReturns403() throws Exception {
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o8','o8@t','h','O8')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_ACCESS_DENIED"));
    }

    // -- Helpers --

    /** Stages a participant row the way the system does now: direct SQL with user identity. */
    private Long insertParticipant() {
        return jdbc.queryForObject(
                "INSERT INTO exam_session_participants (exam_session_id, user_id, status, added_by, version) "
                        + "VALUES (?,?,?,?,0) RETURNING id",
                Long.class, sessionId, studentUserId, "ELIGIBLE", teacherUserId);
    }

    private Long createExamAndPublish(Long subjectId) throws Exception {
        String body = mockMvc.perform(post("/api/exams")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"code\":\"PEX\",\"title\":\"T\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long examId = Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + jdbc.queryForObject("SELECT id FROM questions WHERE code='q'", Long.class) + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", examId);
        entityManager.clear();
        return examId;
    }

    private Long createSession(Long examId, Instant starts, Instant ends) throws Exception {
        String body = mockMvc.perform(post("/api/exam-sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"code\":\"PSEX\",\"title\":\"T\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
