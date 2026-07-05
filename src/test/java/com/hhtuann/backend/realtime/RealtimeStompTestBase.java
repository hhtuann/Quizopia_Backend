package com.hhtuann.backend.realtime;

import com.hhtuann.backend.security.config.SecurityProperties;
import com.hhtuann.backend.security.token.JwtAccessTokenService;
import com.hhtuann.backend.testsupport.MutableClock;
import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import com.hhtuann.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared base for STOMP-over-WebSocket integration tests (Day 7 Realtime). Boots the full app on a
 * random port, issues real HS256 access tokens via {@link JwtAccessTokenService}, and connects a
 * real {@link WebSocketStompClient} carrying the {@code Authorization: Bearer <token>} CONNECT header.
 *
 * <p>Every {@code connect*} returns a {@link StompTestConnection} (B1R4-A §1) — a holder that owns the
 * client, the session, and a <b>per-connection</b> transport-error future, so two concurrent connections
 * (even of the same principal) never share or race their disconnect signals. Tests must use
 * try-with-resources / finally to guarantee the client is stopped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class, OutboundProbeConfig.class})
public abstract class RealtimeStompTestBase {

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtAccessTokenService tokenService;
    @Autowired protected MutableClock clock;
    @Autowired protected JwtEncoder jwtEncoder;
    @Autowired protected SecurityProperties securityProperties;
    @Autowired protected OutboundMessageProbe outboundProbe;
    @Autowired protected AcceptedSubscriptionProbe acceptedProbe;
    @Autowired protected com.hhtuann.backend.realtime.support.RepositoryLockEntryProbe lockProbe;

    // --- suite-wide gates (B1R4-A2 §1/§2) ---
    // Before EACH test: no harness client may be left running from a prior test, and the observation
    // probes start clean. The counter is NOT reset here — a leak from a previous test must surface.
    @BeforeEach
    void assertNoOutstandingClientsBeforeAndResetProbes() {
        assertThat(ClientRegistry.outstanding())
                .as("no harness WebSocketStompClient may be outstanding before a test (leak from a prior test?)")
                .isZero();
        outboundProbe.clear();
        acceptedProbe.clear();
        lockProbe.reset();
    }

    // After EACH test: every connect (success or failure) must have balanced created/stopped, and the
    // observation probes must have hit no internal failure (predicate throw / parse error). Runs after
    // subclass @AfterEach and after try-with-resources close(), so it catches any leak.
    @AfterEach
    void assertNoOutstandingClientsAndHealthyProbesAfter() {
        assertThat(ClientRegistry.outstanding())
                .as("no harness WebSocketStompClient may remain running after a test")
                .isZero();
        assertThat(outboundProbe.failures())
                .as("OutboundMessageProbe must record no internal observation failure")
                .isEmpty();
        assertThat(acceptedProbe.failures())
                .as("AcceptedSubscriptionProbe must record no internal observation failure")
                .isEmpty();
    }

    /**
     * A {@code ByteArrayMessageConverter} that accepts ANY content type (the broker emits
     * {@code application/json}, which the base converter — supporting only {@code application/octet-stream}
     * — would drop). Returns raw bytes; tests decode to String. Also serializes {@code byte[]} payloads
     * for SEND (so a client SEND frame reaches the server, where the guard rejects it).
     */
    protected static final org.springframework.messaging.converter.MessageConverter LENIENT_BYTES_CONVERTER =
            new org.springframework.messaging.converter.ByteArrayMessageConverter() {
                @Override
                protected boolean supportsMimeType(MessageHeaders headers) {
                    return true;
                }
            };

    protected String wsUrl() {
        return "ws://localhost:" + port + "/ws";
    }

    // --- outbound probe helpers (B1R4-A1 §4) ---

    /** Predicate matching an outbound {@code SERVER_TIME_SYNC} message. */
    protected static Predicate<OutboundMessageProbe.Captured> serverTimeSyncMessage() {
        return c -> c.payload() != null && c.payload().contains("SERVER_TIME_SYNC");
    }

    /** Predicate matching any outbound {@code ERROR} frame. */
    protected static Predicate<OutboundMessageProbe.Captured> errorFrame() {
        return c -> c.command() == org.springframework.messaging.simp.stomp.StompCommand.ERROR;
    }

    /**
     * Awaits one outbound message matching {@code predicate} (real server-side observation). Fails on
     * timeout — the matching message MUST arrive. Call {@code outboundProbe.clear()} first to scope the
     * observation window to messages sent after setup.
     */
    protected OutboundMessageProbe.Captured awaitOutbound(
            Predicate<OutboundMessageProbe.Captured> predicate, long timeoutSeconds) throws Exception {
        return outboundProbe.expect(predicate).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Asserts NO outbound message matching {@code predicate} arrives within the bounded window — real
     * server-side evidence of absence (not a bare latch). The timeout itself is the success signal.
     * Call {@code outboundProbe.clear()} first so already-captured messages do not satisfy the predicate.
     */
    protected void assertNoOutbound(Predicate<OutboundMessageProbe.Captured> predicate, long timeoutSeconds) {
        assertThatThrownBy(() -> outboundProbe.expect(predicate).get(timeoutSeconds, TimeUnit.SECONDS))
                .as("no outbound message matching the predicate should arrive within " + timeoutSeconds + "s")
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    // --- accepted-subscription probe helpers (B1R4-A2 §3) ---

    /** Predicate matching an accepted SUBSCRIBE to exactly {@code destination}. */
    protected static Predicate<AcceptedSubscriptionProbe.Accepted> acceptedDestination(String destination) {
        return a -> destination.equals(a.destination());
    }

    /**
     * Awaits proof the server ACCEPTED a SUBSCRIBE matching {@code predicate} — i.e. the inbound interceptors
     * passed the frame and it entered inbound processing (SessionSubscribeEvent fires after authorization).
     * This proves AUTHORIZATION + inbound receipt; it is NOT broker-registration proof (B2F8: the broker
     * registers the subscription later, asynchronously on the brokerChannel; for SERVER_TIME_SYNC delivery,
     * {@code ServerTimeSyncLifecycleIntegrationTests} additionally awaits the outbound + client stages).
     * Fails on timeout.
     */
    protected AcceptedSubscriptionProbe.Accepted awaitAccepted(
            Predicate<AcceptedSubscriptionProbe.Accepted> predicate, long timeoutSeconds) throws Exception {
        return acceptedProbe.expect(predicate).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /** Asserts the server did NOT accept a SUBSCRIBE matching {@code predicate} — no SessionSubscribeEvent
     *  was published (an authorized SUBSCRIBE fires the event after the inbound interceptors pass; absence
     *  proves the interceptor rejected it before publication). */
    protected void assertNoAccepted(Predicate<AcceptedSubscriptionProbe.Accepted> predicate, long timeoutSeconds) {
        assertThatThrownBy(() -> acceptedProbe.expect(predicate).get(timeoutSeconds, TimeUnit.SECONDS))
                .as("no SUBSCRIBE matching the predicate should be authorized (no SessionSubscribeEvent)")
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    /** Creates a teacher with a school + PUBLISHED exam version + an OPEN session they own; returns the sessionId. */
    protected long createTeacherOwnedOpenSession(String tag) {
        long teacherId = insertUserWithRole(tag, "TEACHER");
        long school = ins("INSERT INTO schools (code, name) VALUES ('" + tag + "S','Sch')");
        long gl = ins("INSERT INTO grade_levels (school_id, code, name) VALUES (" + school + ",'GL','G')");
        long subj = ins("INSERT INTO subjects (school_id, grade_level_id, code, name) VALUES (" + school + "," + gl + ",'M','M')");
        ins("INSERT INTO teacher_profiles (user_id, school_id, teacher_code) VALUES (" + teacherId + "," + school + ",'TC" + tag + "')");
        long exam = ins("INSERT INTO exams (school_id, subject_id, owner_teacher_id, code, title) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherId + "),'E','E')");
        long ver = ins("INSERT INTO exam_versions (school_id, exam_id, version_number, status, total_points, published_at, created_by) VALUES (" + school + "," + exam + ",1,'PUBLISHED',1,now()," + teacherId + ")");
        long sec = ins("INSERT INTO exam_sections (exam_version_id, title, position) VALUES (" + ver + ",'S',0)");
        long bank = ins("INSERT INTO question_banks (school_id, subject_id, owner_teacher_id, code, name) VALUES (" + school + "," + subj + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherId + "),'B','B')");
        long q = ins("INSERT INTO questions (question_bank_id, code, created_by) VALUES (" + bank + ",'Q'," + teacherId + ")");
        long qv = ins("INSERT INTO question_versions (question_id, version_number, question_type, content, default_points, metadata, created_by) VALUES (" + q + ",1,'SINGLE_CHOICE','c',1,'{}'::jsonb," + teacherId + ")");
        long eq = ins("INSERT INTO exam_questions (exam_version_id, exam_section_id, source_question_id, source_question_version_id, question_code, question_type, content, default_points, position, metadata) VALUES (" + ver + "," + sec + "," + q + "," + qv + ",'QC','SINGLE_CHOICE','c',1,0,'{}'::jsonb)");
        jdbc.update("INSERT INTO exam_question_options (exam_question_id, option_key, content, is_correct, position) VALUES (" + eq + ",'A','a',false,0),(" + eq + ",'B','b',false,1),(" + eq + ",'C','c',true,2),(" + eq + ",'D','d',false,3)");
        Instant now = Instant.parse("2026-07-04T08:00:00Z");
        return ins("INSERT INTO exam_sessions (school_id, exam_version_id, owner_teacher_id, code, title, status, starts_at, ends_at, max_attempts, created_by, opened_at) VALUES ("
                + school + "," + ver + ",(SELECT id FROM teacher_profiles WHERE user_id=" + teacherId + "),'S" + tag + "','t','OPEN','" + now.minusSeconds(3600) + "','" + now.plusSeconds(7200) + "',2," + teacherId + ",'" + now.minusSeconds(3600) + "')");
    }

    protected long teacherIdForSession(long sessionId) {
        return jdbc.queryForObject("SELECT u.id FROM users u JOIN teacher_profiles tp ON tp.user_id=u.id JOIN exam_sessions s ON s.owner_teacher_id=tp.id WHERE s.id=" + sessionId, Long.class);
    }

    protected long ins(String sql) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class);
    }

    /** Issues a valid access token with the REAL system time (not the MutableClock) so the production
     *  JwtDecoder (which validates exp against the system clock) accepts it. */
    protected String accessToken(Long userId, String username, List<String> roleCodes) {
        Instant realNow = Instant.now();
        Instant exp = realNow.plus(securityProperties.getJwt().getAccessTokenLifetime());
        return craftCustomToken(userId, exp, securityProperties.getJwt().getIssuer(), securityProperties.getJwt().getAudience());
    }

    /** Crafts a real HS256 JWT with custom claims (expired / wrong-issuer / wrong-audience matrix). */
    protected String craftCustomToken(Long userId, Instant expiresAt, String issuer, String audience) {
        return craftCustomToken(userId, Instant.now(), expiresAt, issuer, audience);
    }

    /** Crafts a real HS256 JWT with custom issuedAt + expiresAt (for deterministic expired tokens). */
    protected String craftCustomToken(Long userId, Instant issuedAt, Instant expiresAt, String issuer, String audience) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).audience(List.of(audience)).subject(String.valueOf(userId))
                .claim("token_version", 0).claim("username", "test").claim("roles", List.of("STUDENT"))
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return jwt.getTokenValue();
    }

    /** Inserts a user, grants the role, returns the userId (tokenVersion defaults to 0). */
    protected long insertUserWithRole(String tag, String roleCode) {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (username, email, password_hash, display_name) VALUES ('" + tag + "','" + tag + "@t.com','h','" + tag + "') RETURNING id",
                Long.class);
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code='" + roleCode + "'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (" + userId + "," + roleId + ")");
        return userId;
    }

    /** Connects a STOMP client with the given bearer token; throws on a rejected CONNECT. */
    protected StompTestConnection connect(String token) {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        return connectWith(headers);
    }

    /** Connects with a raw {@code Authorization} header value (null → missing header). */
    protected StompTestConnection connectRawAuth(String authorizationHeaderValue) {
        StompHeaders headers = new StompHeaders();
        if (authorizationHeaderValue != null) {
            headers.add("Authorization", authorizationHeaderValue);
        }
        return connectWith(headers);
    }

    /**
     * Low-level connect with exact STOMP CONNECT headers. Returns a {@link StompTestConnection} whose
     * per-connection transport-error future is captured in the handler. Throws on a rejected CONNECT
     * (the connect future completes exceptionally and {@code future.get} rethrows).
     */
    protected StompTestConnection connectWith(StompHeaders connectHeaders) {
        return connectWith(connectHeaders, wsUrl());
    }

    /** Connects to an exact {@code url} (e.g. with a query string) and exact CONNECT headers. */
    protected StompTestConnection connectWith(StompHeaders connectHeaders, String url) {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        ClientRegistry.created(); // track every harness-created client; paired with close()/stop-on-failure
        client.setMessageConverter(LENIENT_BYTES_CONVERTER);
        CompletableFuture<StompSession> connectFuture = new CompletableFuture<>();
        CompletableFuture<Throwable> transportError = new CompletableFuture<>();
        CompletableFuture<String> errorFrame = new CompletableFuture<>();
        client.connectAsync(url, (org.springframework.web.socket.WebSocketHttpHeaders) null, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(org.springframework.messaging.simp.stomp.StompSession session, StompHeaders connectedHeaders) {
                        connectFuture.complete(session);
                    }

                    @Override
                    public void handleTransportError(org.springframework.messaging.simp.stomp.StompSession session, Throwable exception) {
                        connectFuture.completeExceptionally(exception);
                        transportError.complete(exception); // per-connection disconnect signal
                    }

                    // ERROR frames are dispatched to the SESSION handler (not a subscription) — capture the
                    // first one per connection for the §5 no-leak assertions. byte[] so the lenient
                    // converter hands us the raw ERROR body.
                    @Override
                    public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        String reason = headers.getFirst("message");
                        String body = (payload instanceof byte[] b)
                                ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                                : (payload == null ? "" : String.valueOf(payload));
                        errorFrame.complete((reason == null ? "" : reason) + " | " + body);
                    }
                });
        try {
            org.springframework.messaging.simp.stomp.StompSession session = connectFuture.get(10, TimeUnit.SECONDS);
            return new StompTestConnection(client, session, transportError, errorFrame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopClientOnConnectFailure(client, e);
            throw new RuntimeException("CONNECT interrupted", e); // unreachable: stopClientOnConnectFailure throws
        } catch (Exception e) {
            // CONNECT rejected or timed out — stop the client (no leak) and account for it. A stop()
            // failure is preserved as a suppressed exception on the connect failure (never swallowed).
            stopClientOnConnectFailure(client, e);
            throw new RuntimeException("CONNECT rejected or timed out", e); // unreachable: stopClientOnConnectFailure throws
        }
    }

    /**
     * Stops a client whose CONNECT failed and decrements the {@link ClientRegistry}. Wraps the original
     * connect failure, attaching any {@code client.stop()} failure as a suppressed exception so neither
     * error is lost. Always rethrows (the caller's {@code throw} above is dead code kept for clarity).
     */
    private static void stopClientOnConnectFailure(WebSocketStompClient client, Throwable connectFailure) {
        RuntimeException toThrow = (connectFailure instanceof RuntimeException re)
                ? re : new RuntimeException("CONNECT failed", connectFailure);
        try {
            client.stop();
        } catch (Throwable stopFailure) {
            try { client.stop(); } catch (Throwable ignored) { /* best-effort second stop */ }
            toThrow.addSuppressed(stopFailure);
        }
        try {
            ClientRegistry.stopped();
        } catch (Throwable registryFailure) {
            toThrow.addSuppressed(registryFailure);
        }
        throw toThrow;
    }
}
