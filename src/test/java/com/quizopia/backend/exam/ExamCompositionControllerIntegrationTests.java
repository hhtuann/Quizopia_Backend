package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
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
 * HTTP-layer integration tests for PUT /api/exams/{examId}/draft/composition
 * using MockMvc + real SecurityFilterChain + real ExamAuthorizationService +
 * real repositories on PostgreSQL 17 Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamCompositionControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private Long teacherUserId;
    private Long subjectId;
    private Long sourceQuestionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('cp','cp@t','h','CP')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        subjectId = insert("INSERT INTO subjects (grade_level_id, code, name) VALUES ((SELECT id FROM grade_levels WHERE code='G10'), 'SUB','Sub')");
        // Source bank + single-choice question owned by the caller.
        long bank = insert("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES (" + teacherUserId + "," + subjectId + ",'QB','Bank')");
        sourceQuestionId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + sourceQuestionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'A','opt',true,0)");
        insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'B','opt',false,1)");
        insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'C','opt',false,2)");
        insert("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'D','opt',false,3)");
    }

    @Test
    void putCompositionReturns200() throws Exception {
        Long examId = createExam("EX1");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"durationMinutes\":60,\"instructions\":\"Read\","
                                + "\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":"
                                + "[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0,\"defaultPoints\":2.00}]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composition[0].questions[0].sourceQuestionId").value(sourceQuestionId))
                .andExpect(jsonPath("$.composition[0].questions[0].questionType").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.instructions").value("Read"))
                // Editor response may expose answer (teacher editor); never exam.version.
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void putCompositionUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(put("/api/exams/1/draft/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putCompositionForeignOwnerReturns403() throws Exception {
        Long examId = createExam("EX2");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o4','o4@t','h','O4')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0}]}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_ACCESS_DENIED"));
    }

    @Test
    void putCompositionForeignBankQuestionReturns403() throws Exception {
        // Composition sources must come from the caller's own banks
        // (user-centric ownership; subject scope no longer matters).
        Long examId = createExam("EX5");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o5','o5@t','h','O5')");
        long foreignBank = insert("INSERT INTO question_banks (created_by_user_id, code, name) VALUES (" + other + ",'FB','Foreign')");
        long foreignQuestion = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + foreignBank + ",'fq1','ACTIVE',1," + other + ")");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + foreignQuestion + ",\"position\":0}]}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_ACCESS_DENIED"));
    }

    @Test
    void putCompositionBlankSectionTitleReturns400() throws Exception {
        Long examId = createExam("EX3");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExamVersion\":0,\"sections\":[{\"position\":0,\"title\":\"\",\"questions\":[]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXAM_VALIDATION_ERROR"));
    }

    @Test
    void putCompositionMalformedJsonReturns400Not500() throws Exception {
        Long examId = createExam("EX4");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest());
    }

    // -- Helpers --

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
