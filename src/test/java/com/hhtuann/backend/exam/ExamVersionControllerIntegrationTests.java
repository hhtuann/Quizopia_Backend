package com.hhtuann.backend.exam;

import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
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
 * HTTP-layer integration tests for POST /api/exams/{examId}/versions (A3.2-2B) using
 * MockMvc + real SecurityFilterChain + real ExamAuthorizationService + real repositories
 * on PostgreSQL 17 Testcontainers. The source PUBLISHED version is staged via jdbc (the
 * publish endpoint lands in a later checkpoint).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamVersionControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long teacherUserId;
    private Long schoolId;
    private Long subjectId;
    private Long sourceQuestionId;

    @BeforeEach
    void setUp() {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('vc','vc@t','h','VC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('VCS','VC School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'VCT')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'QB','Bank')");
        sourceQuestionId = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q1','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + sourceQuestionId + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            insertNoReturn("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
    }

    @Test
    void createVersionReturns201() throws Exception {
        Long examId = createAndPublishExam("EX1");
        mockMvc.perform(post("/api/exams/" + examId + "/versions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cloneFromVersionNumber\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.clonedFrom").value(1));
    }

    @Test
    void createVersionNullBodyClonesLatest201() throws Exception {
        Long examId = createAndPublishExam("EX2");
        mockMvc.perform(post("/api/exams/" + examId + "/versions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clonedFrom").value(1));
    }

    @Test
    void createVersionUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exams/1/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createVersionForeignOwnerReturns403() throws Exception {
        Long examId = createAndPublishExam("EX3");
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('o5','o5@t','h','O5')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        insertNoReturn("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + schoolId + ",'VC2')");
        mockMvc.perform(post("/api/exams/" + examId + "/versions")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_ACCESS_DENIED"));
    }

    @Test
    void createVersionCloneFromZeroReturns400() throws Exception {
        // Closes the F1 coverage gap from the A3.2-2B review: @Positive rejects cloneFrom<=0.
        Long examId = createAndPublishExam("EX5");
        mockMvc.perform(post("/api/exams/" + examId + "/versions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cloneFromVersionNumber\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXAM_VALIDATION_ERROR"));
    }

    // -- Helpers --

    /** Creates an exam via POST, composes it via PUT, then flips v1 to PUBLISHED (+exam READY) via jdbc. */
    private Long createAndPublishExam(String code) throws Exception {
        Long examId = createExam(code);
        // PUT composition with the single source question.
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":"
                                + "[{\"sourceQuestionId\":" + sourceQuestionId + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        // Flip v1 DRAFT -> PUBLISHED (publish endpoint not implemented yet).
        Long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        // The PUT composition left v1 managed as DRAFT; clear the persistence context so the
        // POST /versions handler re-reads v1 as PUBLISHED from the DB (not the stale managed copy).
        entityManager.clear();
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

    private void insertNoReturn(String sql) {
        jdbc.update(sql);
    }
}
