package com.hhtuann.backend.realtime;

import com.hhtuann.backend.realtime.event.RealtimeEventType;
import com.hhtuann.backend.realtime.support.FailingRealtimePublisher;
import com.hhtuann.backend.realtime.support.RealtimeTestSupportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP source-of-truth failure isolation (B1R4-B1 §5). The outbound failure is injected, then the
 * REAL HTTP endpoint is driven via MockMvc — not the service directly. Asserts the HTTP response is
 * the contract success (not a 5xx), the DB committed (the authoritative GET reflects the transition),
 * and the response body carries no broker exception / sensitive marker.
 *
 * <p>NOT {@code @Transactional} — the controller's tx must commit so AFTER_COMMIT (and the injected
 * broker failure) actually fires, and the authoritative GET can read the committed state.
 */
@AutoConfigureMockMvc
@Import(RealtimeTestSupportConfig.class)
class RealtimeHttpFailureIsolationIntegrationTests extends RealtimeStompTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private FailingRealtimePublisher publisher;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private long sessionId;
    private long teacherId;
    private long studentId;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("hf-" + tag);
        teacherId = teacherIdForSession(sessionId);
        studentId = insertUserWithRole("hf-stu-" + tag, "STUDENT");
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        ins("INSERT INTO student_profiles (user_id, school_id, student_code) VALUES (" + studentId + "," + school + ",'SC" + tag + "')");
        jdbc.update("INSERT INTO exam_session_participants (school_id, exam_session_id, student_profile_id, added_by) VALUES ("
                + school + "," + sessionId + ",(SELECT id FROM student_profiles WHERE user_id=" + studentId + ")," + teacherId + ")");
        publisher.reset();
    }

    @Test
    void attemptStartHttpSuccessDespiteBrokerFailure() throws Exception {
        publisher.failNext((type, sid) -> type == RealtimeEventType.ATTEMPT_STARTED && sid.equals(sessionId));

        // POST the REAL start endpoint — the ATTEMPT_STARTED send fails, but the response is the contract 201.
        String body = mockMvc.perform(post("/api/exam-sessions/" + sessionId + "/attempts")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(studentId))))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).as("response must not leak the broker exception / sensitive marker")
                .doesNotContain(FailingRealtimePublisher.SENSITIVE_MARKER);

        long attemptId = parseAttemptId(body);
        // Authoritative GET reflects the committed transition (REST is source of truth).
        mockMvc.perform(get("/api/attempts/" + attemptId).with(jwt().jwt(j -> j.subject(String.valueOf(studentId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.attemptId").value(attemptId));
    }

    @Test
    void sessionOpenHttpSuccessDespiteBrokerFailure() throws Exception {
        long school = jdbc.queryForObject("SELECT school_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long ver = jdbc.queryForObject("SELECT exam_version_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long owner = jdbc.queryForObject("SELECT owner_teacher_id FROM exam_sessions WHERE id=" + sessionId, Long.class);
        long sched = ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by) VALUES ("
                + school + "," + ver + "," + owner + ",'HFSCH','t','SCHEDULED','"
                + Instant.now().minusSeconds(3600) + "','" + Instant.now().plusSeconds(7200) + "',2," + teacherId + ")");
        publisher.failNext((type, sid) -> type == RealtimeEventType.SESSION_OPENED && sid.equals(sched));

        // POST the REAL open endpoint — SESSION_OPENED send fails, but the response is the contract 200.
        mockMvc.perform(post("/api/exam-sessions/" + sched + "/open")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // Authoritative GET reflects the committed OPEN state.
        mockMvc.perform(get("/api/exam-sessions/" + sched).with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private long parseAttemptId(String body) {
        try {
            return objectMapper.readTree(body).get("attemptId").asLong();
        } catch (Exception e) {
            throw new RuntimeException("failed to parse attemptId from response: " + body, e);
        }
    }
}
