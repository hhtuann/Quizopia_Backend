package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for the 4 lifecycle endpoints (A3.2-3C). MockMvc + real PG17. Session state
 * for open/close is staged via jdbc (the focus here is the HTTP contract, not the state machine).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
class ExamSessionLifecycleControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private long teacherUserId;
    private long schoolId;
    private long draftSessionId;
    private long scheduledSessionId;
    private long openSessionId;

    @BeforeEach
    void setUp() throws Exception {
        teacherUserId = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('lcc','lcc@t','h','LCC')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", teacherUserId);
        schoolId = insert("INSERT INTO schools (code, name) VALUES ('LCH','Lifecycle HTTP School')");
        long gl = insert("INSERT INTO grade_levels (school_id, code, name) VALUES (" + schoolId + ",'GL','G')");
        long subjectId = insert("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + schoolId + "," + gl + ",'SUB','Sub')");
        long tp = insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherUserId + "," + schoolId + ",'LHC')");
        long bank = insert("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + schoolId + "," + subjectId + "," + tp + ",'LHB','Bank')");
        long q = insert("INSERT INTO questions (question_bank_id, code, status, current_version_number, created_by) VALUES (" + bank + ",'q','ACTIVE',1," + teacherUserId + ")");
        long v = insert("INSERT INTO question_versions (question_id, version_number, question_type, content, difficulty, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c','MEDIUM',1,'{}'::jsonb," + teacherUserId + ")");
        for (Object[] o : new Object[][]{{"A", true, 0}, {"B", false, 1}, {"C", false, 2}, {"D", false, 3}}) {
            jdbc.update("INSERT INTO question_options (question_version_id, option_key, content, is_correct, position) VALUES (" + v + ",'" + o[0] + "','opt'," + o[1] + "," + o[2] + ")");
        }
        String examBody = mockMvc.perform(post("/api/exams").with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId))))
                        .contentType("application/json").content("{\"subjectId\":" + subjectId + ",\"code\":\"LHE\",\"title\":\"T\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long examId = firstJsonId(examBody);
        mockMvc.perform(put("/api/exams/" + examId + "/draft/composition").with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId))))
                        .contentType("application/json").content("{\"expectedVersionNumber\":1,\"sections\":[{\"position\":0,\"title\":\"S\",\"questions\":[{\"sourceQuestionId\":" + q + ",\"position\":0}]}]}"))
                .andExpect(status().isOk());
        Long v1 = jdbc.queryForObject("SELECT id FROM exam_versions WHERE exam_id=? AND status='DRAFT'", Long.class, examId);
        jdbc.update("UPDATE exam_versions SET status='PUBLISHED', published_at=now(), total_points=1.00 WHERE id=?", v1);
        jdbc.update("UPDATE exams SET status='READY' WHERE id=?", examId);
        // Clear the persistence context: PUT composition loaded v1 as a managed DRAFT entity, so the
        // JDBC PUBLISHED flip must be re-read (not served from the first-level cache) by createSession.
        entityManager.clear();

        Instant futureStarts = Instant.now().plusSeconds(3600);
        Instant futureEnds = futureStarts.plusSeconds(7200);
        draftSessionId = createSession(examId, "LHD", futureStarts, futureEnds);

        Instant midStarts = Instant.now().minusSeconds(3600);
        Instant midEnds = Instant.now().plusSeconds(3600);
        scheduledSessionId = createSession(examId, "LHS", midStarts, midEnds);
        jdbc.update("UPDATE exam_sessions SET status='SCHEDULED' WHERE id=?", scheduledSessionId);

        openSessionId = createSession(examId, "LHO", midStarts, midEnds);
        jdbc.update("UPDATE exam_sessions SET status='OPEN', opened_at=now() WHERE id=?", openSessionId);
        // Clear the PC so the staged SCHEDULED/OPEN states are re-read by the lifecycle endpoints
        // (createSession left them as managed DRAFT entities; findByIdForUpdate would serve those).
        entityManager.clear();
    }

    @Test
    void scheduleReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + draftSessionId + "/schedule")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.openedAt").doesNotExist())
                .andExpect(jsonPath("$.answerKey").doesNotExist());
    }

    @Test
    void openReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + scheduledSessionId + "/open")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openedAt").exists());
    }

    @Test
    void closeReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + openSessionId + "/close")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists());
    }

    @Test
    void cancelReturns200() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + draftSessionId + "/cancel")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void scheduleUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/" + draftSessionId + "/schedule"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignOwnerReturns403() throws Exception {
        long other = insert("INSERT INTO users (username, email, password_hash, display_name) VALUES ('lco','lco@t','h','LCO')");
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code='TEACHER'", other);
        insert("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + other + "," + schoolId + ",'LOC')");
        mockMvc.perform(post("/api/exam-sessions/" + draftSessionId + "/schedule")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(other)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_ACCESS_DENIED"));
    }

    @Test
    void missingSessionReturns404() throws Exception {
        mockMvc.perform(post("/api/exam-sessions/999999/cancel")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAM_SESSION_NOT_FOUND"));
    }

    // -- Helpers --

    private long createSession(long examId, String code, Instant starts, Instant ends) throws Exception {
        String body = mockMvc.perform(post("/api/exam-sessions").with(jwt().jwt(j -> j.subject(String.valueOf(teacherUserId))))
                        .contentType("application/json")
                        .content("{\"examId\":" + examId + ",\"examVersionNumber\":1,\"code\":\"" + code + "\",\"title\":\"T\",\"startsAt\":\"" + starts + "\",\"endsAt\":\"" + ends + "\",\"maxAttempts\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return firstJsonId(body);
    }

    /** Extract the FIRST {@code "id":<n>} from a JSON body (avoids capturing nested ids like subject.id). */
    private static long firstJsonId(String body) {
        String after = body.substring(body.indexOf("\"id\":") + 5);
        return Long.parseLong(after.replaceAll("[^0-9].*$", "").trim());
    }

    private long insert(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
