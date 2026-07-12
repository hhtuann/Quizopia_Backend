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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-layer integration tests for the 4 exam endpoints using MockMvc + real
 * SecurityFilterChain + real ExamAuthorizationService + real repositories.
 * Proves HTTP status codes, security filter, validation handler,
 * anti-enumeration,
 * and JSON serialization on PostgreSQL 17 Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private Long teacherUserId;
    private Long schoolId;
    private Long subjectId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('ct','ct@t','h','CT')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('CTS','CT School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl
                + ",'SUB','Sub')");
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES ("
                + teacherUserId + "," + schoolId + ",'CTC')");
    }

    // -- Success smoke (4 endpoints) --

    @Test
    void getPurposesReturns200() throws Exception {
        insert("INSERT INTO exam_purposes (school_id, code, title, position) VALUES (" + schoolId + ",'MID','Mid',0)");
        mockMvc.perform(get("/api/exam-purposes").with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("MID"))
                .andExpect(jsonPath("$.items[0].schoolId").doesNotExist());
    }

    @Test
    void postExamReturns201() throws Exception {
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"EX1\",\"title\":\"Exam 1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("EX1"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currentVersionNumber").value(1))
                .andExpect(jsonPath("$.hasDraft").value(true));
    }

    @Test
    void getMyExamsReturns200() throws Exception {
        // Create an exam first
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"EX2\",\"title\":\"Exam 2\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/exams/my").with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("EX2"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getExamDetailReturns200() throws Exception {
        String created = mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"EX3\",\"title\":\"Exam 3\"}"))
                .andReturn().getResponse().getContentAsString();
        Long examId = extractId(created);

        mockMvc.perform(get("/api/exams/" + examId).with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("EX3"))
                .andExpect(jsonPath("$.currentDraftVersion.versionNumber").value(1))
                .andExpect(jsonPath("$.currentDraftVersion.durationMinutes").value(60))
                .andExpect(jsonPath("$.currentDraftVersion.tfMatrixScoring.['0']").value(0))
                .andExpect(jsonPath("$.currentDraftVersion.tfMatrixScoring.['4']").value(100));
    }

    // -- Unauthenticated (4 endpoints → 401) --

    @Test
    void getPurposesUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/exam-purposes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postExamUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exams")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":1,\"code\":\"X\",\"title\":\"T\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyExamsUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/exams/my"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getExamDetailUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/exams/1"))
                .andExpect(status().isUnauthorized());
    }

    // -- Validation --

    @Test
    void postExamBlankCode_isAutoGenerated() throws Exception {
        // code is now optional (auto-generated server-side); a blank code succeeds with
        // a non-empty auto code.
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"\",\"title\":\"T\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test
    void postExamMissingSubjectIdReturns400() throws Exception {
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"X\",\"title\":\"T\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postExamMalformedJsonReturns400Not500() throws Exception {
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{broken"))
                .andExpect(status().isBadRequest());
    }

    // -- GlobalExceptionHandler proof --

    @Test
    void getExamDetailForeignOwnerReturns404ExamNotFound() throws Exception {
        // Create exam as teacher1
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"FX\",\"title\":\"T\"}"))
                .andReturn();
        Long examId = jdbc.queryForObject("SELECT id FROM exams WHERE code='FX'", Long.class);

        // Access as different teacher
        long otherUser = insert(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('o2','o2@t','h','O2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'",
                otherUser);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + otherUser + "," + schoolId
                + ",'TC2')");

        mockMvc.perform(get("/api/exams/" + examId).with(jwt().jwt(j -> j.subject(String.valueOf(otherUser)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_NOT_FOUND"));
    }

    @Test
    void postExamDuplicateCodeReturns409ExamCodeConflict() throws Exception {
        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"DUP\",\"title\":\"T\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/exams")
                .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":" + subjectId + ",\"code\":\"dup\",\"title\":\"T2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAM_CODE_CONFLICT"));
    }

    // -- Route proof --

    @Test
    void getMyExamsRouteNotCapturedByExamId() throws Exception {
        // /api/exams/my must hit the literal route, not /{examId}
        mockMvc.perform(get("/api/exams/my").with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk());
    }

    // -- Serialization proof --

    @Test
    void getPurposesNoSchoolIdInResponse() throws Exception {
        insert("INSERT INTO exam_purposes (school_id, code, title, position) VALUES (" + schoolId + ",'T1','T1',0)");
        mockMvc.perform(get("/api/exam-purposes").with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].schoolId").doesNotExist())
                .andExpect(jsonPath("$.items[0].createdAt").doesNotExist());
    }

    // -- Helpers --

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    private Long extractId(String json) {
        // Simple extraction of "id":123 from JSON response
        int idx = json.indexOf("\"id\":");
        return Long.parseLong(json.substring(idx + 5).replaceAll("[^0-9].*$", "").trim());
    }
}
