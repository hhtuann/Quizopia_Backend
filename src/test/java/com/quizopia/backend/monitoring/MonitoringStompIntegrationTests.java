package com.quizopia.backend.monitoring;

import com.quizopia.backend.attempt.application.AttemptService;
import com.quizopia.backend.attempt.dto.StartAttemptRequest;
import com.quizopia.backend.monitoring.application.AttemptActivityIngestService;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchRequest;
import com.quizopia.backend.realtime.RealtimeStompTestBase;
import com.quizopia.backend.realtime.event.RealtimeEventEnvelope;
import com.quizopia.backend.realtime.event.RealtimeEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-A4 STOMP delivery (issue #83): after a real (committed) activity-event
 * ingest, an authorized teacher subscribed to the EXISTING
 * {@code /topic/exam-sessions/{sessionId}} topic receives an
 * {@code ATTEMPT_ACTIVITY} envelope naming the activity type and provenance —
 * with NO metadata and NO answer content (REST timeline is the source of
 * truth). The negative leg proves the untouched subscribe interceptor still
 * denies a student on the teacher topic.
 *
 * <p>Extends {@link RealtimeStompTestBase} for the booted app, probes, clock
 * and fixtures. The base's {@code StompTestConnection} wrapper is
 * package-private to {@code com.quizopia.backend.realtime}, so this class
 * carries its own minimal tracked client with the same CONNECT/ERROR
 * semantics (real token in the CONNECT headers, per-connection error and
 * transport-error futures).
 */
class MonitoringStompIntegrationTests extends RealtimeStompTestBase {

    private static final int AWAIT_SECONDS = 15;

    @Autowired private AttemptActivityIngestService ingestService;
    @Autowired private AttemptService attemptService;
    @Autowired private ObjectMapper objectMapper;

    private long sessionId;
    private long teacherId;
    private long studentId;
    private long attemptId;
    private String teacherToken;
    private String studentToken;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        sessionId = createTeacherOwnedOpenSession("mon-" + tag);
        teacherId = teacherIdForSession(sessionId);
        teacherToken = accessToken(teacherId, "mon-tch", List.of("TEACHER"));
        studentId = insertUserWithRole("mon-stu-" + tag, "STUDENT");
        studentToken = accessToken(studentId, "mon-stu", List.of("STUDENT"));
        jdbc.update("INSERT INTO exam_session_participants (user_id, exam_session_id, added_by) VALUES ("
                + studentId + "," + sessionId + "," + teacherId + ")");
        attemptId = attemptService.startAttempt(studentId, sessionId, new StartAttemptRequest(null)).attemptId();
    }

    @Test
    void authorizedTeacherClientReceivesAttemptActivityEnvelopeAfterCommit() throws Exception {
        try (TestStompClient teacher = TestStompClient.connect(teacherToken, wsUrl())) {
            CopyOnWriteArrayList<String> frames = new CopyOnWriteArrayList<>();
            CountDownLatch activityReceived = new CountDownLatch(1);
            teacher.subscribe(topic(), frameSink(frames, activityReceived));
            awaitAccepted(acceptedDestination(topic()), 10); // authorized SUBSCRIBE accepted

            outboundProbe.clear(); // scope the observation window to this ingest
            UUID eventId = UUID.randomUUID();
            ActivityEventBatchRequest batch = new ActivityEventBatchRequest(List.of(
                    new ActivityEventBatchRequest.Item(eventId, "TAB_HIDDEN",
                            Instant.parse("2026-07-04T07:59:50Z"), 1L,
                            UUID.fromString("00000000-0000-0000-0000-000000000003"),
                            objectMapper.readTree("{\"note\":\"never-on-the-wire\"}"))));

            // Direct service call: @Transactional commits on return, then the
            // AFTER_COMMIT broadcaster delivers the delta ping.
            ingestService.ingest(studentId, attemptId, batch);
            // Server-side evidence first (outbound frames match on destination +
            // payload: the STOMP command is stamped later, during encoding).
            awaitOutbound(c -> topic().equals(c.destination())
                    && c.payload() != null && c.payload().contains("ATTEMPT_ACTIVITY"), 10);
            assertThat(activityReceived.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the subscribed authorized teacher receives the ATTEMPT_ACTIVITY envelope")
                    .isTrue();

            String payload = frames.stream()
                    .filter(f -> f.contains("ATTEMPT_ACTIVITY") && f.contains("TAB_HIDDEN"))
                    .findFirst().orElseThrow();
            RealtimeEventEnvelope envelope = objectMapper.readValue(payload, RealtimeEventEnvelope.class);
            assertThat(envelope.eventType()).isEqualTo(RealtimeEventType.ATTEMPT_ACTIVITY.name());
            assertThat(envelope.activityType()).isEqualTo("TAB_HIDDEN");
            assertThat(envelope.activitySource()).isEqualTo("CLIENT");
            assertThat(envelope.sessionId()).isEqualTo(sessionId);
            assertThat(envelope.attemptId()).isEqualTo(attemptId);
            assertThat(envelope.userId()).isEqualTo(studentId);
            assertThat(envelope.clientOccurredAt()).isNotNull();
            assertThat(envelope.occurredAt()).isNotNull();
            assertThat(envelope.serverTime()).isNotNull();
            assertThat(payload)
                    .as("delta ping carries NO metadata and NO answer content — REST timeline is the source of truth")
                    .doesNotContain("metadata")
                    .doesNotContain("answer")
                    .doesNotContain("never-on-the-wire");

            // The durable row backs the notification (REST refetch finds it).
            Integer stored = jdbc.queryForObject(
                    "SELECT count(*) FROM attempt_activity_events WHERE attempt_id=? AND event_id=?",
                    Integer.class, attemptId, eventId);
            assertThat(stored).isEqualTo(1);
        }
    }

    @Test
    void unauthorizedStudentSubscribeToTeacherTopicDenied() throws Exception {
        try (TestStompClient student = TestStompClient.connect(studentToken, wsUrl())) {
            student.subscribe(topic(), noopHandler());
            String error = student.awaitError(AWAIT_SECONDS);
            assertThat(error).as("the interceptor answers with a generic ERROR frame").isNotBlank();
            student.awaitTransportError(10); // the server closes the rejected connection
            assertNoAccepted(acceptedDestination(topic()), 2); // never reached inbound acceptance
        }
    }

    // === helpers ===

    private String topic() {
        return "/topic/exam-sessions/" + sessionId;
    }

    private static StompFrameHandler frameSink(List<String> sink, CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                sink.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                latch.countDown();
            }
        };
    }

    private static StompFrameHandler noopHandler() {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { /* never expected */ }
        };
    }

    /**
     * Minimal tracked STOMP client (the base's {@code StompTestConnection} is
     * package-private to {@code com.quizopia.backend.realtime}): real token in
     * the CONNECT headers, per-connection ERROR-frame and transport-error
     * futures, lenient byte converter, and guaranteed stop on close.
     */
    private static final class TestStompClient implements AutoCloseable {

        private final WebSocketStompClient client;
        private final StompSession session;
        private final CompletableFuture<String> errorFrame;
        private final CompletableFuture<Throwable> transportError;

        private TestStompClient(WebSocketStompClient client, StompSession session,
                CompletableFuture<String> errorFrame, CompletableFuture<Throwable> transportError) {
            this.client = client;
            this.session = session;
            this.errorFrame = errorFrame;
            this.transportError = transportError;
        }

        static TestStompClient connect(String token, String url) throws Exception {
            WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
            client.setMessageConverter(LENIENT_BYTES_CONVERTER);
            CompletableFuture<StompSession> connected = new CompletableFuture<>();
            CompletableFuture<String> connectErrorFrame = new CompletableFuture<>();
            CompletableFuture<Throwable> connectTransportError = new CompletableFuture<>();
            StompHeaders headers = new StompHeaders();
            headers.add("Authorization", "Bearer " + token);
            client.connectAsync(url, (WebSocketHttpHeaders) null, headers, new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
                    connected.complete(s);
                }

                @Override
                public void handleTransportError(StompSession s, Throwable exception) {
                    connected.completeExceptionally(exception);
                    connectTransportError.complete(exception);
                }

                @Override
                public Type getPayloadType(StompHeaders h) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders h, Object payload) {
                    String reason = h.getFirst("message");
                    String body = payload instanceof byte[] b
                            ? new String(b, StandardCharsets.UTF_8)
                            : (payload == null ? "" : String.valueOf(payload));
                    connectErrorFrame.complete((reason == null ? "" : reason) + " | " + body);
                }
            });
            return new TestStompClient(client, connected.get(10, TimeUnit.SECONDS),
                    connectErrorFrame, connectTransportError);
        }

        void subscribe(String destination, StompFrameHandler handler) {
            session.subscribe(destination, handler);
        }

        String awaitError(long timeoutSeconds) throws Exception {
            return errorFrame.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        void awaitTransportError(long timeoutSeconds) throws Exception {
            transportError.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (RuntimeException alreadyClosed) {
                // best-effort: the server may have closed the socket first
            }
            try {
                client.stop();
            } catch (RuntimeException alreadyStopped) {
                // best-effort second stop
            }
        }
    }
}
