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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-layer integration tests for POST /api/exams/{examId}/publish (A3.2-2C) using MockMvc
 * + real SecurityFilterChain + real ExamAuthorizationService + real repositories on PostgreSQL
 * 17 Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamPublishControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long teacherUserId;
    private Long schoolId;
    private Long subjectId;
    private Long sourceQuestionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('pc','pc@t','h','PC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('PCS','PC School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'PCT')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'QB','Bank')");
        sourceQuestionId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + sourceQuestionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
    }

    @Test
    void publishReturns200AndNoAnswerLeak() throws Exception {
        Long examId = createAndComposeExam("EX1");
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.questionCount").value(1))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andExpect(jsonPath("$.answerKey").doesNotExist())
                .andExpect(jsonPath("$.isCorrect").doesNotExist());
    }

    @Test
    void publishAbsentBodyReturns200() throws Exception {
        Long examId = createAndComposeExam("EX2");
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publishUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exams/1/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishForeignOwnerReturns403() throws Exception {
        Long examId = createAndComposeExam("EX3");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o6','o6@t','h','O6')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + schoolId + ",'PC2')");
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other))))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_ACCESS_DENIED"));
    }

    @Test
    void publishTwiceReturns409Conflict() throws Exception {
        Long examId = createAndComposeExam("EX4");
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk());
        entityManager.clear();
        mockMvc.perform(post("/api/exams/" + examId + "/publish")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_PUBLISH_CONFLICT"));
    }

    // -- Helpers --

    private Long createAndComposeExam(String code) throws Exception {
        Long examId = createExam(code);
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":"
                                + "[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        return examId;
    }

    private Long createExam(String code) throws Exception {
        String body = mockMvc.perform(post("/api/exams")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"code\":\"" + code + "\",\"title\":\"T\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int idx = body.indexOf("\"id\":");
        return Long.parseLong(body.substring(idx + 5).replaceAll("[^0-9].*$", "").trim());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
