package com.quizopia.backend.monitoring;

import com.nimbusds.jwt.SignedJWT;
import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.attempt.dto.StartAttemptResponse;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;
import com.quizopia.backend.monitoring.application.CameraPreflightService;
import com.quizopia.backend.monitoring.dto.MediaTokenResponse;
import com.quizopia.backend.monitoring.dto.PreflightStartResponse;
import com.quizopia.backend.monitoring.dto.PreflightVerifyResponse;
import com.quizopia.backend.testsupport.MutableClock;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P4-B2/B3/B4 camera preflight integration tests (issues #84–#87): preflight
 * create/verify lifecycle, one-time hashed readiness leases, media-token
 * renewal, teacher camera-status aggregation / viewer token / revocation.
 * The LiveKit gateway is replaced by an in-memory fake; tokens are minted by
 * the real {@code LiveKitTokenService} against test-only keys.
 */
@SpringBootTest(properties = {
        "quizopia.media.livekit.enabled=true",
        "quizopia.media.livekit.api-key=testkey",
        "quizopia.media.livekit.api-secret=test-livekit-secret-0123456789abcdef012345"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class, MediaGatewayTestConfig.class})
@Transactional
class CameraPreflightServiceIntegrationTests {


    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private FakeMediaRoomGateway gateway;
    @Autowired private LiveKitProperties liveKit;
    @Autowired private CameraPreflightService preflightService;
    @Autowired private AttemptService attemptService;

    private Instant now;
    private long teacherId;
    private long studentId;
    private long otherStudentId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-14T08:00:00Z");
        clock.setInstant(now);
        gateway.reset();
        String tag = "cpf" + System.nanoTime() % 1_000_000;
        teacherId = user("t" + tag, "Teacher One");
        grant(teacherId, "TEACHER");
        studentId = user("s" + tag, "Student One");
        grant(studentId, "STUDENT");
        otherStudentId = user("o" + tag, "Outsider");
        grant(otherStudentId, "STUDENT");
        long examId = exam(tag);
        sessionId = session(examId, "OPEN", "SS" + tag);
        monitored(sessionId);
        memberOf(tag, sessionId, studentId);
    }

    // === 1. preflight create ===

    @Test
    void preflightHappyPathReturnsTokenFieldsNoStoreAndUpsertsRoomIdempotently() throws Exception {
        ResultActions first = postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId);
        first.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.preflightId").isNotEmpty())
                .andExpect(jsonPath("$.mediaToken").isNotEmpty())
                .andExpect(jsonPath("$.mediaIdentity").isNotEmpty())
                .andExpect(jsonPath("$.wsUrl").value("ws://localhost:7880"))
                .andExpect(jsonPath("$.roomScope").value("session"))
                .andExpect(jsonPath("$.cameraRequired").value(true))
                .andExpect(jsonPath("$.mediaTokenExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        String room = roomName(sessionId);
        assertThat(room).startsWith("s-").hasSize(34).matches("[a-z0-9-]+");
        UUID preflightId = UUID.fromString(jdbc.queryForObject(
                "SELECT id FROM monitoring_preflights WHERE exam_session_id=? AND user_id=?",
                UUID.class, sessionId, studentId).toString());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM monitoring_preflights WHERE id=?", String.class, preflightId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_media_bindings b JOIN monitoring_preflights p ON p.id=b.preflight_id "
                        + "WHERE b.exam_session_id=? AND b.user_id=? AND b.epoch=1 AND b.attempt_id IS NULL "
                        + "AND b.active_to IS NULL", Integer.class, sessionId, studentId))
                .isEqualTo(1);

        // Second preflight for the same user: SAME room (upsert idempotent),
        // prior ACTIVE preflight expired, binding epoch advanced.
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId)
                .andExpect(status().isOk());
        assertThat(roomName(sessionId)).isEqualTo(room);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_preflights WHERE exam_session_id=? AND user_id=? AND status='ACTIVE'",
                Integer.class, sessionId, studentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitoring_preflights WHERE exam_session_id=? AND user_id=? AND status='EXPIRED'",
                Integer.class, sessionId, studentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT MAX(epoch) FROM monitoring_media_bindings WHERE exam_session_id=? AND user_id=?",
                Integer.class, sessionId, studentId)).isEqualTo(2);
    }

    @Test
    void studentTokenGrantIsCameraPublishOnly() throws Exception {
        PreflightStartResponse response = preflightService.createPreflight(studentId, sessionId);
        SignedJWT token = SignedJWT.parse(response.mediaToken());
        var claims = token.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("testkey");
        assertThat(claims.getSubject()).isEqualTo(response.mediaIdentity());
        assertThat(claims.getExpirationTime().toInstant())
                .isEqualTo(now.plus(liveKit.getMediaTokenTtl()));
        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) claims.getClaim("video");
        assertThat(video).containsExactlyInAnyOrderEntriesOf(Map.of(
                "roomJoin", true,
                "room", roomName(sessionId),
                "canPublish", true,
                "canPublishSources", java.util.List.of("camera"),
                "canSubscribe", false,
                "canPublishData", false));
    }

    @Test
    void preflightNotMonitoredSessionConflicts() throws Exception {
        jdbc.update("DELETE FROM session_monitoring_policies WHERE exam_session_id=?", sessionId);
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_PREFLIGHT_NOT_APPLICABLE"));
    }

    @Test
    void preflightIneligibleStudentForbidden() throws Exception {
        postJson(otherStudentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_ELIGIBLE"));
    }

    @Test
    void preflightSessionNotOpenConflicts() throws Exception {
        String tag = "sc" + System.nanoTime() % 1_000_000;
        long examId = exam(tag);
        long scheduled = ins("INSERT INTO exam_sessions (exam_id, code, title, status, starts_at, ends_at, "
                + "max_attempts, created_by, duration_minutes) VALUES (" + examId + ",'SC" + tag + "','S',"
                + "'SCHEDULED','" + now.plusSeconds(600) + "','" + now.plusSeconds(7200) + "',2,"
                + teacherId + ",60)");
        jdbc.update("INSERT INTO session_monitoring_policies (exam_session_id, monitoring_enabled) "
                + "VALUES (?, true)", scheduled);
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", scheduled)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_SESSION_NOT_OPEN"));
    }

    @Test
    void preflightExistingInProgressAttemptConflicts() throws Exception {
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, started_at, "
                        + "deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?)",
                sessionId, studentId, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)));
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_ALREADY_ACTIVE"));
    }

    @Test
    void preflightInfraDisabledIs503() throws Exception {
        liveKit.setEnabled(false);
        try {
            postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights", sessionId)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("MONITORING_INFRA_UNAVAILABLE"));
        } finally {
            liveKit.setEnabled(true);
        }
    }

    // === 2. verify ===

    @Test
    void verifyWithoutPublishedCameraIsNotPublished() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                created.preflightId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.readinessLease").doesNotExist())
                .andExpect(jsonPath("$.readinessExpiresAt").doesNotExist());
    }

    @Test
    void verifyMutedCameraIsNotPublished() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        gateway.publishCamera(roomName(sessionId), created.mediaIdentity(), "tr1");
        gateway.muteCamera(roomName(sessionId), created.mediaIdentity());
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                created.preflightId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_PUBLISHED"));
    }

    @Test
    void verifyPublishedCameraGrantsHashedLeaseRawLeaseNeverPersisted() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        gateway.publishCamera(roomName(sessionId), created.mediaIdentity(), "track-abc");

        PreflightVerifyResponse verified = preflightService.verifyPreflight(
                studentId, sessionId, created.preflightId());
        assertThat(verified.state()).isEqualTo("READY");
        assertThat(verified.verifiedCameraTrackSid()).isEqualTo("track-abc");
        assertThat(verified.readinessLease()).isNotBlank().hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(verified.readinessExpiresAt()).isEqualTo(now.plus(liveKit.getReadinessLeaseTtl()));

        String hash = jdbc.queryForObject(
                "SELECT lease_hash FROM monitoring_preflights WHERE id=?", String.class, created.preflightId());
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}")
                .isEqualTo(sha256Hex(verified.readinessLease()));

        // The RAW lease must not appear in ANY text column of the row.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CAST(id AS text) AS id, media_identity, status, lease_hash, verified_track_sid "
                        + "FROM monitoring_preflights WHERE id=?", created.preflightId());
        assertThat(row.values()).noneMatch(v -> String.valueOf(v).equals(verified.readinessLease()));
    }

    @Test
    void verifyRepeatRotatesLeaseWhileStayingVerified() {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        gateway.publishCamera(roomName(sessionId), created.mediaIdentity(), "track-abc");

        PreflightVerifyResponse first = preflightService.verifyPreflight(studentId, sessionId, created.preflightId());
        clock.advance(java.time.Duration.ofSeconds(5));
        PreflightVerifyResponse second = preflightService.verifyPreflight(studentId, sessionId, created.preflightId());

        assertThat(second.state()).isEqualTo("READY");
        assertThat(second.readinessLease()).isNotEqualTo(first.readinessLease());
        assertThat(second.readinessExpiresAt())
                .isEqualTo(clock.instant().plus(liveKit.getReadinessLeaseTtl()));
        assertThat(jdbc.queryForObject("SELECT lease_hash FROM monitoring_preflights WHERE id=?",
                String.class, created.preflightId()))
                .isEqualTo(sha256Hex(second.readinessLease()));
        assertThat(jdbc.queryForObject("SELECT status FROM monitoring_preflights WHERE id=?",
                String.class, created.preflightId())).isEqualTo("VERIFIED");
    }

    @Test
    void verifyExpiredPreflightConflicts() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        clock.advance(liveKit.getPreflightTtl().plusSeconds(1));
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                created.preflightId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_PREFLIGHT_EXPIRED"));
    }

    @Test
    void verifyInfraFailureIs503() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        gateway.failWith(new TwirpMediaRoomGateway.LiveKitUnavailableException("down", null));
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                created.preflightId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MONITORING_INFRA_UNAVAILABLE"));
    }

    @Test
    void verifyNotOwnerForbiddenAndUnknownPreflightNotFound() throws Exception {
        PreflightStartResponse created = preflightService.createPreflight(studentId, sessionId);
        postJson(otherStudentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                created.preflightId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MONITORING_ACCESS_DENIED"));
        postJson(studentId, "/api/exam-sessions/{id}/monitoring/preflights/{pid}/verify", sessionId,
                UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITORING_PREFLIGHT_NOT_FOUND"));
    }

    // === 3. attempt media-token renewal ===

    @Test
    void attemptMediaTokenRenewalMintsSameIdentityToken() throws Exception {
        StartAttemptResponse attempt = startMonitoredAttempt();
        ResultActions result = postJson(studentId, "/api/attempts/{id}/monitoring/media-token", attempt.attemptId());
        result.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mediaToken").isNotEmpty())
                .andExpect(jsonPath("$.mediaIdentity").isNotEmpty())
                .andExpect(jsonPath("$.wsUrl").value("ws://localhost:7880"))
                .andExpect(jsonPath("$.roomScope").value("session"))
                .andExpect(jsonPath("$.mediaTokenExpiresAt").isNotEmpty());

        MediaTokenResponse renewed = preflightService.attemptMediaToken(studentId, attempt.attemptId());
        SignedJWT token = SignedJWT.parse(renewed.mediaToken());
        // Same opaque identity as the preflight (published track survives).
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(renewed.mediaIdentity());
        String bindingIdentity = jdbc.queryForObject(
                "SELECT b.media_identity FROM monitoring_media_bindings b WHERE b.attempt_id=?",
                String.class, attempt.attemptId());
        assertThat(renewed.mediaIdentity()).isEqualTo(bindingIdentity);
    }

    @Test
    void attemptMediaTokenAfterRevokeForbidden() {
        StartAttemptResponse attempt = startMonitoredAttempt();
        String identity = jdbc.queryForObject(
                "SELECT media_identity FROM monitoring_media_bindings WHERE attempt_id=?",
                String.class, attempt.attemptId());
        preflightService.revokeParticipant(teacherId, sessionId, identity);
        assertThatThrownBy(() -> preflightService.attemptMediaToken(studentId, attempt.attemptId()))
                .isInstanceOf(com.quizopia.backend.monitoring.exception.MonitoringException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_CAMERA_REVOKED"));
    }

    @Test
    void attemptMediaTokenWithoutBindingConflicts() throws Exception {
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, started_at, "
                        + "deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?)",
                sessionId, studentId, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)));
        Long attemptId = jdbc.queryForObject(
                "SELECT id FROM attempts WHERE exam_session_id=? AND user_id=?", Long.class, sessionId, studentId);
        postJson(studentId, "/api/attempts/{id}/monitoring/media-token", attemptId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_BINDING_MISSING"));
    }

    @Test
    void attemptMediaTokenNotMonitoredConflicts() throws Exception {
        jdbc.update("DELETE FROM session_monitoring_policies WHERE exam_session_id=?", sessionId);
        jdbc.update("INSERT INTO attempts (exam_session_id, user_id, attempt_number, status, started_at, "
                        + "deadline_at) VALUES (?,?,1,'IN_PROGRESS',?,?)",
                sessionId, studentId, java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)));
        Long attemptId = jdbc.queryForObject(
                "SELECT id FROM attempts WHERE exam_session_id=? AND user_id=?", Long.class, sessionId, studentId);
        postJson(studentId, "/api/attempts/{id}/monitoring/media-token", attemptId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MONITORING_PREFLIGHT_NOT_APPLICABLE"));
    }

    @Test
    void attemptMediaTokenNotOwnerForbidden() throws Exception {
        StartAttemptResponse attempt = startMonitoredAttempt();
        postJson(otherStudentId, "/api/attempts/{id}/monitoring/media-token", attempt.attemptId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ATTEMPT_ACCESS_DENIED"));
    }

    // === 4./5. teacher endpoints ===

    @Test
    void teacherMediaTokenGrantsSubscribeOnly() throws Exception {
        ResultActions result = mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/monitoring/media-token")
                .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))));
        result.andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mediaIdentity").value("teacher-" + teacherId))
                .andExpect(jsonPath("$.roomScope").value("session"));
        MediaTokenResponse token = preflightService.teacherMediaToken(teacherId, sessionId);
        SignedJWT parsed = SignedJWT.parse(token.mediaToken());
        @SuppressWarnings("unchecked")
        Map<String, Object> video = (Map<String, Object>) parsed.getJWTClaimsSet().getClaim("video");
        assertThat(video).containsExactlyInAnyOrderEntriesOf(Map.of(
                "roomJoin", true,
                "room", roomName(sessionId),
                "canPublish", false,
                "canSubscribe", true,
                "canPublishData", false));
    }

    @Test
    void teacherEndpointsDenyForeignTeacherAndStudents() throws Exception {
        String tag = "ft" + System.nanoTime() % 1_000_000;
        long foreign = user("f" + tag, "Foreign Teacher");
        grant(foreign, "TEACHER");
        String path = "/api/exam-sessions/" + sessionId + "/monitoring/camera-status";
        mockMvc.perform(get(path).with(jwt().jwt(j -> j.subject(String.valueOf(foreign)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MONITORING_ACCESS_DENIED"));
        mockMvc.perform(get(path).with(jwt().jwt(j -> j.subject(String.valueOf(studentId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MONITORING_ACCESS_DENIED"));
        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/monitoring/media-token")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(foreign)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cameraStatusAggregatesAllParticipantStates() throws Exception {
        // Three learners (clock advances between flows so the active_from
        // ordering below is deterministic):
        String tag = "cs" + System.nanoTime() % 1_000_000;
        long reconnectingUser = user("c" + tag, "Reconnecting User");
        grant(reconnectingUser, "STUDENT");
        memberOf(tag, sessionId, reconnectingUser);
        long idleUser = user("d" + tag, "Idle User");
        grant(idleUser, "STUDENT");
        memberOf(tag + "d", sessionId, idleUser);

        // connected: bound IN_PROGRESS attempt + open camera connection row
        StartAttemptResponse connected = startMonitoredAttempt();
        openConnection(connected.attemptId(), now.minusSeconds(30));
        // reconnecting: bound IN_PROGRESS attempt, no connection (dropped)
        clock.advance(java.time.Duration.ofSeconds(5));
        StartAttemptResponse reconnecting = startMonitoredAttempt(reconnectingUser);
        // idle: binding whose attempt was submitted (finished → IDLE)
        clock.advance(java.time.Duration.ofSeconds(5));
        StartAttemptResponse idle = startMonitoredAttempt(idleUser);
        jdbc.update("UPDATE attempts SET status='SUBMITTED', submitted_at=now(), "
                + "submission_idempotency_key='K" + tag + "' WHERE id=" + idle.attemptId());

        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/monitoring/camera-status")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomScope").value("session"))
                .andExpect(jsonPath("$.participants.length()").value(3))
                .andExpect(jsonPath("$.participants[0].state").value("CONNECTED"))
                .andExpect(jsonPath("$.participants[0].displayName").value("Student One"))
                .andExpect(jsonPath("$.participants[0].userId").value(studentId))
                .andExpect(jsonPath("$.participants[0].attemptId").value(connected.attemptId()))
                .andExpect(jsonPath("$.participants[0].connectedSince").isNotEmpty())
                .andExpect(jsonPath("$.participants[0].lastVerifiedAt").isNotEmpty())
                .andExpect(jsonPath("$.participants[1].state").value("RECONNECTING"))
                .andExpect(jsonPath("$.participants[1].displayName").value("Reconnecting User"))
                .andExpect(jsonPath("$.participants[1].attemptId").value(reconnecting.attemptId()))
                .andExpect(jsonPath("$.participants[1].connectedSince").value((Object) null))
                .andExpect(jsonPath("$.participants[2].state").value("IDLE"))
                .andExpect(jsonPath("$.participants[2].displayName").value("Idle User"))
                .andExpect(jsonPath("$.participants[2].attemptId").value(idle.attemptId()))
                .andExpect(jsonPath("$.participants[2].connectedSince").value((Object) null));

        // revoked: teacher revokes the connected one → state flips, camera
        // event recorded, renewal denied, kick issued.
        String identity = jdbc.queryForObject(
                "SELECT media_identity FROM monitoring_media_bindings WHERE attempt_id=?",
                String.class, connected.attemptId());
        preflightService.revokeParticipant(teacherId, sessionId, identity);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT source, metadata->>'mediaIdentity' AS ident FROM attempt_activity_events "
                        + "WHERE attempt_id=? AND event_type='CAMERA_REVOKED'", connected.attemptId());
        assertThat(event.get("source")).isEqualTo("SERVER");
        assertThat(event.get("ident")).isEqualTo(identity);
        assertThat(gateway.removed).contains(roomName(sessionId) + "/" + identity);
        assertThatThrownBy(() -> preflightService.attemptMediaToken(studentId, connected.attemptId()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_CAMERA_REVOKED"));

        mockMvc.perform(get("/api/exam-sessions/" + sessionId + "/monitoring/camera-status")
                        .with(jwt().jwt(j -> j.subject(String.valueOf(teacherId)))))
                .andExpect(jsonPath("$.participants[0].state").value("REVOKED"));

        // double revoke → 409
        assertThatThrownBy(() -> preflightService.revokeParticipant(teacherId, sessionId, identity))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MONITORING_CAMERA_ALREADY_REVOKED"));
    }

    // === helpers ===

    /** Full learner flow: preflight → publish → verify → monitored start. */
    private StartAttemptResponse startMonitoredAttempt() {
        return startMonitoredAttempt(studentId);
    }

    private StartAttemptResponse startMonitoredAttempt(long userId) {
        PreflightStartResponse created = preflightService.createPreflight(userId, sessionId);
        String room = roomName(sessionId);
        gateway.publishCamera(room, created.mediaIdentity(), "track-" + created.preflightId());
        PreflightVerifyResponse verified = preflightService.verifyPreflight(userId, sessionId, created.preflightId());
        assertThat(verified.state()).isEqualTo("READY");
        return attemptService.startAttempt(userId, sessionId,
                new StartAttemptRequest(null, verified.readinessLease()));
    }

    /** sha256 hex of the raw lease — mirrors the backend's only persisted form. */
    private static String sha256Hex(String raw) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void openConnection(Long attemptId, Instant connectedAt) {
        UUID bindingId = jdbc.queryForObject(
                "SELECT id FROM monitoring_media_bindings WHERE attempt_id=?", UUID.class, attemptId);
        jdbc.update("INSERT INTO monitoring_camera_connections (id, binding_id, track_sid, connected_at, "
                        + "disconnected_at, last_verified_at, close_reason) VALUES (?,?, 'tr',?,?,?,'WEBHOOK')",
                UUID.randomUUID(), bindingId, java.sql.Timestamp.from(connectedAt), null,
                java.sql.Timestamp.from(connectedAt.plusSeconds(10)));
    }

    private ResultActions postJson(long userId, String path, Object... vars) throws Exception {
        return mockMvc.perform(post(path, vars)
                .with(jwt().jwt(j -> j.subject(String.valueOf(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":1}"));
    }

    private String roomName(long sid) {
        return jdbc.queryForObject(
                "SELECT room_name FROM monitoring_media_rooms WHERE exam_session_id=?", String.class, sid);
    }

    private static String code(Throwable e) {
        return ((com.quizopia.backend.monitoring.exception.MonitoringException) e)
                .getErrorCode().code();
    }

    private long user(String username, String displayName) {
        return ins("INSERT INTO users (username, email, password_hash, display_name) VALUES ('"
                + username + "','" + username + "@t.com','h','" + displayName + "')");
    }

    private void grant(long userId, String role) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT " + userId
                + ", id FROM roles WHERE code='" + role + "'");
    }

    private long exam(String tag) {
        long gl = ins("INSERT INTO grade_levels (code, name) VALUES ('GL" + tag + "','G')");
        long subj = ins("INSERT INTO subjects (grade_level_id, code, name) VALUES (" + gl + ",'" + tag + "','M')");
        long bank = ins("INSERT INTO question_banks (created_by_user_id, subject_id, code, name) VALUES ("
                + teacherId + "," + subj + ",'B" + tag + "','Bank')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacherId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, "
                + "default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherId + ")");
        long examId = ins("INSERT INTO exams (created_by_user_id, subject_id, code, title, status, published_at, "
                + "total_points) VALUES (" + teacherId + "," + subj + ",'E" + tag + "','E','PUBLISHED',now(),1)");
        long sec = ins("INSERT INTO exam_sections (exam_id, title, position) VALUES (" + examId + ",'S',0)");
        long eq = ins("INSERT INTO exam_questions (exam_id, exam_section_id, source_question_id, "
                + "source_question_version_id, question_code, question_type, content, default_points, position, "
                + "metadata) VALUES (" + examId + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) "
                + "VALUES (" + eq + ",'A','a',true,0),(" + eq + ",'B','b',false,1),("
                + eq + ",'C','c',false,2),(" + eq + ",'D','d',false,3)");
        return examId;
    }

    private long session(long examId, String status, String code) {
        return ins("INSERT INTO exam_sessions (exam_id, code, title, status, visibility, starts_at, ends_at, "
                + "max_attempts, created_by, opened_at, duration_minutes) VALUES (" + examId + ",'" + code
                + "','S','" + status + "','CLASS_RESTRICTED','"
                + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherId
                + ",'" + now.minusSeconds(3600) + "',60)");
    }

    private void monitored(long sessionId) {
        jdbc.update("INSERT INTO session_monitoring_policies (exam_session_id, monitoring_enabled) "
                + "VALUES (?, true)", sessionId);
    }

    private void memberOf(String tag, long sessionId, long userId) {
        long cls = ins("INSERT INTO classrooms (owner_user_id, code, name) VALUES ("
                + teacherId + ",'C" + tag + "','C')");
        jdbc.update("INSERT INTO classroom_members (classroom_id, user_id) VALUES (" + cls + "," + userId + ")");
        jdbc.update("INSERT INTO exam_session_classes (exam_session_id, classroom_id) VALUES (" + sessionId + "," + cls + ")");
    }

    private long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }
}
