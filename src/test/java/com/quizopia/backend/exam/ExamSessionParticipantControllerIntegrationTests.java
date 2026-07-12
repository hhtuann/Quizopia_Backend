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
    private Long studentProfileId;

    @BeforeEach
    void setUp() throws Exception {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('pc2','pc2@t','h','PC2')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        Long schoolId = insert("INSERT INTO schools (code, name) VALUES ('PCS2','PC2 School')");
        Long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        Long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        Long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'PC2T')");
        Long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'QB','Bank')");
        Long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        Long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        Long examId = createExamAndPublish(schoolId, subjectId);
        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = starts.plusSeconds(7200);
        sessionId = createSession(examId, starts, ends);
        Long sUser = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('s1','s1@t','h','Student1')");
        studentProfileId = insert("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + sUser + "," + schoolId + ",'HS001')");
    }

    @Test
    void addParticipantsReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentProfileIds\":[" + studentProfileId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
    }

    @Test
    void listParticipantsReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentProfileIds\":[" + studentProfileId + "]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].studentProfileId").value(studentProfileId))
                .andExpect(jsonPath("$.items[0].displayName").value("Student1"))
                .andExpect(jsonPath("$.items[0].studentCode").value("HS001"))
                .andExpect(jsonPath("$.items[0].answerKey").doesNotExist());
    }

    @Test
    void blockUnblockReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentProfileIds\":[" + studentProfileId + "]}"))
                .andExpect(status().isOk());
        Long pid = jdbc.queryForObject("SELECT id FROM exam_session_participants WHERE exam_session_id=? AND student_profile_id=?", Long.class, sessionId, studentProfileId);
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
    void addUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/participants")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
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
        jdbc.update("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=?", Long.class, sessionId) + ",'PC3')");
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/participants")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_ACCESS_DENIED"));
    }

    // -- Helpers --

    private Long createExamAndPublish(Long schoolId, Long subjectId) throws Exception {
        String body = mockMvc.perform(post("/api/exams")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":" + subjectId + ",\"code\":\"PEX\",\"title\":\"T\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long examId = Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + jdbc.queryForObject("SELECT id FROM questions WHERE code='q'", Long.class) + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        Long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        entityManager.clear();
        return examId;
    }

    private Long createSession(Long examId, Instant starts, Instant ends) throws Exception {
        String body = mockMvc.perform(post("/api/exam-sessions")
                        .with(jwt().jwt(j -> j.subject(teacherUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":" + examId + ",\"examVersionNumber\":1,\"code\":\"PSEX\",\"title\":\"T\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.substring(body.indexOf("\"id\":") + 5).replaceAll("[^0-9].*$", "").trim());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
