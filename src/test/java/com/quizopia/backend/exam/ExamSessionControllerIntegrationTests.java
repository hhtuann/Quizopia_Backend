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
class ExamSessionControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long teacherUserId;
    private Long subjectId;
    private Long sourceQuestionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('sc','sc@t','h','SC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subjectId + ",'QB','Bank')");
        sourceQuestionId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + sourceQuestionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
    }

    @Test
    void createSessionReturns201() throws Exception {
        Long examId = createPublishedExam("EX1");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        mockMvc.perform(post("/api/exam-sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"code\":\"S1\",\"title\":\"Session\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.code").value("S1"))
                .andExpect(jsonPath("$.participantCount").value(0))
                .andExpect(jsonPath("$.answerKey").doesNotExist());
    }

    @Test
    void createSessionViaNestedRouteWithoutBodyExamIdReturns201() throws Exception {
        // The frontend posts to /api/exams/{examId}/sessions WITHOUT examId in
        // the body (the path is authoritative) — regression for the contract
        // mismatch that 400-rejected every UI session creation.
        Long examId = createPublishedExam("EXN");
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        mockMvc.perform(post("/api/exams/" + examId + "/sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ca thi cuoi ky - Lap trinh Java\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1,\"durationMinutes\":90,\"visibility\":\"CLASS_RESTRICTED\",\"classroomIds\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Ca thi cuoi ky - Lap trinh Java"));
    }

    @Test
    void createSessionWithoutAnyExamIdReturns400() throws Exception {
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        mockMvc.perform(post("/api/exam-sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"No exam\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listMySessionsReturns200() throws Exception {
        Long examId = createPublishedExam("EX2");
        createSession(examId, "L1");
        mockMvc.perform(get("/api/exam-sessions/my").with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("L1"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].answerKey").doesNotExist());
    }

    @Test
    void getSessionDetailReturns200() throws Exception {
        Long examId = createPublishedExam("EX3");
        Long sessionId = createSession(examId, "D1");
        mockMvc.perform(get("/api/exam-sessions/" + sessionId).with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("D1"))
                .andExpect(jsonPath("$.version").exists());
    }

    @Test
    void updateSessionReturns200() throws Exception {
        Long examId = createPublishedExam("EX4");
        Long sessionId = createSession(examId, "U1");
        Instant starts = Instant.now().plusSeconds(5400);
        Instant ends = starts.plusSeconds(3600);
        mockMvc.perform(put("/api/exam-sessions/" + sessionId)
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"title\":\"Updated\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.maxAttempts").value(2))
                .andExpect(jsonPath("$.code").value("U1"));
    }

    @Test
    void createSessionUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exam-sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSessionDetailForeignOwnerReturns403() throws Exception {
        Long examId = createPublishedExam("EX5");
        Long sessionId = createSession(examId, "F1");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o7','o7@t','h','O7')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);

        mockMvc.perform(get("/api/exam-sessions/" + sessionId).with(jwt().jwt(j -> j.subject(String.valueOf(other)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_ACCESS_DENIED"));
    }

    // -- Helpers --

    private Long createPublishedExam(String code) throws Exception {
        String body = mockMvc.perform(post("/api/exams")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"code\":\"" + code + "\",\"title\":\"T\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long examId = Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE exams SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", examId);
        entityManager.clear();
        return examId;
    }

    private Long createSession(Long examId, String code) throws Exception {
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        String body = mockMvc.perform(post("/api/exam-sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"code\":\"" + code + "\",\"title\":\"T\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
