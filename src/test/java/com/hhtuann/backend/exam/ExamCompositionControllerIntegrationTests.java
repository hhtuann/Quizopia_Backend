package com.hhtuann.backend.exam;

import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private Long schoolId;
    private Long subjectId;
    private Long sourceQuestionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('cp','cp@t','h','CP')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('CPS','CP School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'CPT')");
        // Source bank + single-choice question owned by the caller.
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'QB','Bank')");
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
                        .content("{\"expectedVersionNumber\":1,\"durationMinutes\":60,\"instructions\":\"Read\","
                                + "\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":"
                                + "[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0,\"defaultPoints\":2.00}]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDraftVersion.sections[0].questions[0].sourceQuestionId").value(sourceQuestionId))
                .andExpect(jsonPath("$.currentDraftVersion.sections[0].questions[0].questionType").value("SINGLE_CHOICE"))
                .andExpect(jsonPath("$.currentDraftVersion.durationMinutes").value(60))
                .andExpect(jsonPath("$.currentDraftVersion.instructions").value("Read"))
                // Editor response may expose answer (teacher editor); never exam.version.
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void putCompositionUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(put("/api/exams/1/draft/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putCompositionForeignOwnerReturns403() throws Exception {
        Long examId = createExam("EX2");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o4','o4@t','h','O4')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + schoolId + ",'TC4')");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0}]}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_ACCESS_DENIED"));
    }

    @Test
    void putCompositionBlankSectionTitleReturns400() throws Exception {
        Long examId = createExam("EX3");
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"\",\"questions\":[]}]}"))
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
