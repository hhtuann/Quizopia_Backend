package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP slice test for the internal worker path
 * {@code POST /internal/ai/v1/detections} (P4-C, CONTRACT §3.3/§7) with
 * the REAL security chain (permitAll at the chain — the X-Api-Key header
 * IS the authentication, verified in the controller):
 *
 * <ul>
 *   <li>valid key → 200, rows stored with provenance WORKER_VERIFIED,
 *       runtime WORKER_ORT, aggregation + AI_ALERT_RAISED exactly like
 *       the student path;</li>
 *   <li>wrong or missing key → 401 without detail;</li>
 *   <li>unset key (dedicated context) → deny-all 404 — an unconfigured
 *       deployment exposes nothing;</li>
 *   <li>worker items carry attemptId+userId explicitly: bad attempt →
 *       item REJECTED ATTEMPT_NOT_FOUND; foreign user → item REJECTED
 *       NOT_ATTEMPT_OWNER; a browser runtime → 400 (structural
 *       provenance).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@TestPropertySource(properties = {
        "quizopia.monitoring.ai-ingest.worker-key=test-ai-worker-key-0123456789abcdef",
})
@Transactional
class AiWorkerIngestIntegrationTests {

    private static final String PATH = "/internal/ai/v1/detections";
    private static final String API_KEY = "test-ai-worker-key-0123456789abcdef";

    @Autowired private MockMvc mockMvc;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;

    private Instant base;
    private long studentId;
    private long sessionId;
    private long attemptId;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-15T10:00:00Z");
        clock.setInstant(base);
        studentId = newUser(jdbc, "aiworkerstudent");
        sessionId = insertOpenSession(jdbc, studentId, base);
        attemptId = attemptService
                .startAttempt(studentId, sessionId, new StartAttemptRequest(null))
                .attemptId();
        insertPreflightAndBinding(jdbc, sessionId, studentId, attemptId, base, null);
    }

    @Test
    void validKeyAcceptedStoresWorkerVerifiedAndRaisesAlert() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());
        assertThat(response.get("acceptedCount").intValue()).isEqualTo(1);
        assertThat(response.get("results").get(0).get("status").asString())
                .isEqualTo("ACCEPTED");

        assertThat(jdbc.queryForObject(
                "SELECT provenance FROM monitoring_ai_detections", String.class))
                .isEqualTo("WORKER_VERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT runtime FROM monitoring_ai_detections", String.class))
                .isEqualTo("WORKER_ORT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_ai_alerts", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT provenance FROM monitoring_ai_alerts", String.class))
                .isEqualTo("WORKER_VERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attempt_activity_events WHERE event_type='AI_ALERT_RAISED'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void wrongKeyRejected401() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Api-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                .andExpect(status().isUnauthorized());
        assertThat(detectionCount()).isZero();
    }

    @Test
    void missingKeyRejected401() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                .andExpect(status().isUnauthorized());
        assertThat(detectionCount()).isZero();
    }

    @Test
    void unknownAttemptIsItemRejectedNotBatch404() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(999999L, studentId, "WORKER_ORT", base)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());
        assertThat(response.get("rejectedCount").intValue()).isEqualTo(1);
        assertThat(response.get("results").get(0).get("status").asString())
                .isEqualTo("REJECTED");
        assertThat(response.get("results").get(0).get("reason").asString())
                .isEqualTo("ATTEMPT_NOT_FOUND");
    }

    @Test
    void foreignUserIsItemRejectedNotAttemptOwner() throws Exception {
        long other = newUser(jdbc, "aiworkerother");
        MvcResult result = mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, other, "WORKER_ORT", base)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString());
        assertThat(response.get("results").get(0).get("reason").asString())
                .isEqualTo("NOT_ATTEMPT_OWNER");
        assertThat(detectionCount()).isZero();
    }

    @Test
    void browserRuntimeFromWorkerPathRejected400() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "ORT_WEB_WASM", base)))
                .andExpect(status().isBadRequest());
        assertThat(detectionCount()).isZero();
    }

    /**
     * Unset worker-key ⇒ deny-all: the endpoint answers 404 (it pretends
     * not to exist) — same fail-closed posture as an unconfigured
     * LiveKit webhook. Dedicated context: the outer tests configure a
     * real key.
     */
    @Nested
    @TestPropertySource(properties = "quizopia.monitoring.ai-ingest.worker-key=")
    class UnsetKeyDenyAllTests {

        @Autowired private MockMvc mockMvc;

        @Test
        void anyRequestAnswers404EvenWithACorrectLookingKey() throws Exception {
            mockMvc.perform(post(PATH)
                            .header("X-Api-Key", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                    .andExpect(status().isNotFound());
            assertThat(detectionCount()).isZero();
        }
    }

    @Test
    void oversizedBodyRejected413AfterAuthBeforeParse() throws Exception {
        // No key + huge body → 401 (auth FIRST, body never parsed).
        char[] big = new char[300_000];
        java.util.Arrays.fill(big, 'a');
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new String(big)))
                .andExpect(status().isUnauthorized());
        // Valid key + huge body → 413 from the pre-parse guard.
        mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new String(big)))
                .andExpect(status().isPayloadTooLarge());
        assertThat(detectionCount()).isZero();
    }

    @Test
    void freshEventIdWithRepeatedSequenceIsDuplicateNeverAggregates() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                .andExpect(status().isOk());
        assertThat(detectionCount()).isEqualTo(1L);

        // P2-1 replay: SAME sequence, FRESH eventId → the V39 partial unique
        // fires, the item reports DUPLICATE, the alert never grows.
        MvcResult replay = mockMvc.perform(post(PATH)
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBody(attemptId, studentId, "WORKER_ORT", base)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(
                replay.getResponse().getContentAsString());
        assertThat(response.get("duplicateCount").intValue()).isEqualTo(1);
        assertThat(detectionCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT detection_count FROM monitoring_ai_alerts", Integer.class))
                .isEqualTo(1);
    }

    // === helpers ===

    private String workerBody(long attempt, long user, String runtime, Instant observedAt) {
        String body = """
                {"detections":[{"attemptId":%d,"userId":%d,"detection":{
                  "eventId":"%s","eventType":"PHONE_OBSERVED","observedAt":"%s",
                  "sequence":1,"modelId":"yolo26s","modelVersion":"8.4.64-onnx",
                  "runtime":"%s","frameWidth":640,"frameHeight":480,"processingMs":42,
                  "detections":[{"classId":67,"label":"cell phone","confidence":0.91,
                                 "bbox":[0.1,0.2,0.3,0.4]}]}}]}
                """.formatted(attempt, user, UUID.randomUUID(), observedAt, runtime);
        return body;
    }

    private long detectionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM monitoring_ai_detections",
                Long.class);
    }
}
