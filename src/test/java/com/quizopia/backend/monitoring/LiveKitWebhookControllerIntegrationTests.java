package com.quizopia.backend.monitoring;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.cameraEventCount;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.enableMonitoring;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertOpenSession;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertPreflightAndBinding;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.insertRoom;
import static com.quizopia.backend.monitoring.MonitoringCameraFixtures.newUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP slice test for {@code POST /internal/media/v1/livekit/webhooks}
 * (P4-B4, issue #87) with the REAL verifier and the REAL security chain:
 * the wire format is validated end-to-end (raw protojson body + bare HS256
 * JWT whose sha256 claim is base64(plain SHA-256(body))). Garbage/tampered/
 * unsigned/stale deliveries answer 401 without detail; accepted deliveries
 * answer 200 and the duplicate ledger makes redelivery an exact no-op.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@TestPropertySource(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-key=devquizopia",
        "quizopia.media.livekit.api-secret=quizopia-test-livekit-webhook-signing-secret-0123456789",
        "quizopia.monitoring.camera.reconcile-enabled=false",
        "quizopia.monitoring.camera.housekeeping-interval=3600s"
})
@Transactional
class LiveKitWebhookControllerIntegrationTests {

    private static final String SECRET = "quizopia-test-livekit-webhook-signing-secret-0123456789";
    private static final String PATH = "/internal/media/v1/livekit/webhooks";

    @Autowired private MockMvc mockMvc;
    @Autowired private AttemptService attemptService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;

    private Instant base;
    private long attemptId;
    private String identity;

    @BeforeEach
    void setUp() {
        base = Instant.parse("2026-11-14T11:00:00Z");
        clock.setInstant(base);
        long userId = newUser(jdbc, "webstu");
        long sessionId = insertOpenSession(jdbc, userId, base);
        attemptId = attemptService.startAttempt(userId, sessionId, new StartAttemptRequest(null)).attemptId();
        enableMonitoring(jdbc, sessionId);
        insertRoom(jdbc, sessionId);
        identity = insertPreflightAndBinding(jdbc, sessionId, userId, attemptId, base, null)
                .mediaIdentity();
    }

    @Test
    void validSignedTrackPublishedOpensCameraPeriodAndEmitsEvent() throws Exception {
        byte[] body = trackPublishedBody("EV_1", identity, "TR_HTTP_1", freshNanos());

        mockMvc.perform(post(PATH).content(body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, signedAuthorization(body, Duration.ZERO)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_camera_connections "
                        + "WHERE binding_id=(SELECT id FROM monitoring_media_bindings WHERE media_identity=?) "
                        + "AND disconnected_at IS NULL",
                Long.class, identity)).isEqualTo(1);
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM attempt_activity_events WHERE attempt_id=? AND event_type='CAMERA_CONNECTED'",
                String.class, attemptId)).isEqualTo("SERVER");
        assertThat(jdbc.queryForObject(
                "SELECT metadata->>'reason' FROM attempt_activity_events "
                        + "WHERE attempt_id=? AND event_type='CAMERA_CONNECTED'",
                String.class, attemptId)).isEqualTo("webhook");
    }

    @Test
    void duplicateDeliveryIsAcceptedButAppliedOnce() throws Exception {
        byte[] body = trackPublishedBody("EV_2", identity, "TR_HTTP_2", freshNanos());
        String authorization = signedAuthorization(body, Duration.ZERO);

        mockMvc.perform(post(PATH).content(body).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk());
        mockMvc.perform(post(PATH).content(body).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk());

        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_webhook_events", Long.class)).isEqualTo(1);
    }

    @Test
    void tamperedBodyIsRejected401() throws Exception {
        byte[] original = trackPublishedBody("EV_3", identity, "TR_HTTP_3", freshNanos());
        byte[] tampered = new String(original, StandardCharsets.UTF_8)
                .replace("TR_HTTP_3", "TR_EVIL").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post(PATH).content(tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, signedAuthorization(original, Duration.ZERO)))
                .andExpect(status().isUnauthorized());
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isZero();
    }

    @Test
    void missingAuthorizationIsRejected401() throws Exception {
        mockMvc.perform(post(PATH).content(trackPublishedBody("EV_4", identity, "TR_HTTP_4", freshNanos()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageBodyWithBogusTokenIsRejected401() throws Exception {
        mockMvc.perform(post(PATH).content("{\"event\":\"track_published\"}".getBytes(StandardCharsets.UTF_8))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongSecretSignatureIsRejected401() throws Exception {
        byte[] body = trackPublishedBody("EV_5", identity, "TR_HTTP_5", freshNanos());

        mockMvc.perform(post(PATH).content(body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, signedAuthorization(body, Duration.ZERO, "another-secret-that-is-long-enough-for-hs256!!")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleTimestampIsRejected401() throws Exception {
        // 11 minutes old — beyond the 10-minute webhookMaxAge replay window.
        byte[] body = trackPublishedBody("EV_6", identity, "TR_HTTP_6",
                nanosAt(Instant.now().minus(Duration.ofMinutes(11))));

        mockMvc.perform(post(PATH).content(body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, signedAuthorization(body, Duration.ofMinutes(11))))
                .andExpect(status().isUnauthorized());
        assertThat(cameraEventCount(jdbc, attemptId, "CAMERA_CONNECTED")).isZero();
    }

    @Test
    void unknownIdentityIsAcceptedLedgerOnly() throws Exception {
        byte[] body = trackPublishedBody("EV_7", "unknown-identity", "TR_HTTP_7", freshNanos());

        mockMvc.perform(post(PATH).content(body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, signedAuthorization(body, Duration.ZERO)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_camera_connections", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM attempt_activity_events WHERE event_type LIKE 'CAMERA_%'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM monitoring_webhook_events", Long.class)).isEqualTo(1);
    }

    // === wire-format helpers (real LiveKit shape: bare JWT + body sha256) ===

    private static String freshNanos() {
        return nanosAt(Instant.now());
    }

    private static String nanosAt(Instant instant) {
        return String.valueOf(instant.getEpochSecond() * 1_000_000_000L + instant.getNano());
    }

    private static byte[] trackPublishedBody(String eventId, String participantIdentity,
            String trackSid, String timestampNanos) {
        return ("{\"event\": \"track_published\", \"id\": \"" + eventId + "\", "
                + "\"room\": {\"name\": \"room-x\", \"sid\": \"RM_1\"}, "
                + "\"participant\": {\"identity\": \"" + participantIdentity + "\", \"state\": \"ACTIVE\"}, "
                + "\"track\": {\"sid\": \"" + trackSid + "\", \"type\": \"VIDEO\", \"source\": \"CAMERA\", \"muted\": false}, "
                + "\"timestamp\": \"" + timestampNanos + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** Bare JWT (no Bearer prefix) signed with the shared test secret. */
    private static String signedAuthorization(byte[] body, Duration tokenAge) {
        return signedAuthorization(body, tokenAge, SECRET);
    }

    private static String signedAuthorization(byte[] body, Duration tokenAge, String secret) {
        try {
            String digest = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body));
            Instant now = Instant.now();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    new JWTClaimsSet.Builder()
                            .issuer("devquizopia")
                            .claim("sha256", digest)
                            .issueTime(Date.from(now.minus(tokenAge)))
                            .notBeforeTime(Date.from(now.minus(tokenAge).minusSeconds(5)))
                            .expirationTime(Date.from(now.minus(tokenAge).plusSeconds(300)))
                            .jwtID(UUID.randomUUID().toString())
                            .build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("test webhook minting failed", e);
        }
    }
}
