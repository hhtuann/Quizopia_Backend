package com.quizopia.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 7 has no client command over WebSocket — every client {@code SEND} / crafted {@code MESSAGE}
 * frame is rejected before any handler/broadcast/mutation (Day 7 §16, B1R4-A §7, B1R4-A1 §9).
 *
 * <p>§9 evidence per destination: a rejected SEND closes the session (ERROR/disconnect), routes NOTHING
 * through the broker (probe-asserted — no outbound MESSAGE), mutates no DB row, and Day 7 has no
 * {@code @MessageMapping} handler to invoke (structural ApplicationContext proof).
 *
 * <p>Plus the detailed broadcast proof for {@code /topic}: an already-subscribed teacher receives
 * nothing from a spoof SEND. CONNECT+SUBSCRIBE remain unaffected by the guard.
 *
 * <p>All connections use {@link StompTestConnection} try-with-resources. No {@code Thread.sleep}.
 */
class WebSocketSendGuardIntegrationTests extends RealtimeStompTestBase {

    @Autowired private ApplicationContext applicationContext;

    private String studentToken;
    private String tag;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-07-04T08:00:00Z"));
        tag = UUID.randomUUID().toString().substring(0, 6);
        long userId = insertUserWithRole("send-" + tag, "STUDENT");
        studentToken = accessToken(userId, "send", List.of("STUDENT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/exam-sessions/1",
            "/queue/attempt",
            "/user/queue/attempt",
            "/app/anything",
            "/x/y"
    })
    void sendIsRejectedRoutesNothingAndMutatesNothing(String destination) {
        long attemptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Long.class);
        try (StompTestConnection conn = connect(studentToken)) {
            conn.send(destination, "spoof".getBytes(StandardCharsets.UTF_8));
            String error = conn.awaitError(5); // actual ERROR frame received
            conn.awaitDisconnect();            // the attacker connection is closed
            assertErrorHasNoLeak(error, destination);
            // No broker routing: the guard threw in inbound preSend, so no outbound MESSAGE was sent.
            assertNoOutbound(c -> c.command() == StompCommand.MESSAGE, 1);
        }
        long attemptsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Long.class);
        assertThat(attemptsAfter).as("spoof SEND must mutate no DB row").isEqualTo(attemptsBefore);
    }

    @Test
    void sendRejectionErrorsAreGenericAcrossDestinations() {
        // Aggregate proof: all 5 destinations produce the SAME generic ERROR shape (no leak).
        String[] destinations = {"/topic/exam-sessions/1", "/queue/attempt", "/user/queue/attempt", "/app/anything", "/x/y"};
        java.util.List<String> errors = java.util.stream.Stream.of(destinations)
                .map(this::captureSendRejectionError).toList();
        String first = errors.get(0);
        assertThat(errors).as("all SEND-rejection ERROR frames share one generic shape").allMatch(first::equals);
        errors.forEach(e -> assertErrorHasNoLeak(e, null));
    }

    private String captureSendRejectionError(String destination) {
        try (StompTestConnection conn = connect(studentToken)) {
            conn.send(destination, "spoof".getBytes(StandardCharsets.UTF_8));
            String error = conn.awaitError(5);
            conn.awaitDisconnect();
            return error;
        }
    }

    /** Generic no-leak contract for a captured SEND-rejection ERROR frame. */
    private static void assertErrorHasNoLeak(String error, String destination) {
        assertThat(error)
                .as("SEND-rejection ERROR must not leak sensitive data")
                .doesNotContain("MessagingException")
                .doesNotContain("org.springframework")
                .doesNotContain("at com.")
                .doesNotContain("SELECT")
                .doesNotContain("EXAM_SESSION_MONITOR");
        if (destination != null) {
            assertThat(error).as("ERROR must not echo the spoof destination").doesNotContain(destination);
        }
    }

    @Test
    void sendToTopicIsNotBroadcastToActiveSubscriber() throws Exception {
        long sessionId = createTeacherOwnedOpenSession("send-b-" + tag);
        long teacherId = teacherIdForSession(sessionId);
        String teacherToken = accessToken(teacherId, "send-b", List.of("TEACHER"));
        long attemptsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Long.class);

        try (StompTestConnection teacher = connect(teacherToken)) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch topicLatch = new CountDownLatch(1);
            teacher.subscribe("/topic/exam-sessions/" + sessionId, stringHandler(received, topicLatch));
            // Topic readiness: SessionSubscribeEvent fires only after the inbound interceptors pass the
            // topic SUBSCRIBE (authorization + inbound receipt — NOT broker registration per B2F8). No SERVER_TIME_SYNC proxy.
            awaitAccepted(acceptedDestination("/topic/exam-sessions/" + sessionId), 10);

            outboundProbe.clear();
            try (StompTestConnection attacker = connect(studentToken)) {
                attacker.send("/topic/exam-sessions/" + sessionId, "spoof".getBytes(StandardCharsets.UTF_8));
                attacker.awaitDisconnect();
            }
            // The spoof never reached the broker — zero delivery to the active subscriber.
            assertThat(topicLatch.await(2, TimeUnit.SECONDS)).isFalse();
            assertThat(received).isEmpty();
            assertNoOutbound(c -> c.command() == StompCommand.MESSAGE, 1);
        }

        long attemptsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Long.class);
        assertThat(attemptsAfter).isEqualTo(attemptsBefore);
    }

    @Test
    void subscribeStillWorksAfterConnect() throws Exception {
        try (StompTestConnection conn = connect(studentToken)) {
            conn.subscribe("/user/queue/attempt", noopHandler(new CountDownLatch(1)));
            awaitAccepted(acceptedDestination("/user/queue/attempt"), 10);
            assertThat(conn.isConnected()).isTrue();
        }
    }

    @Test
    void noClientCommandMessageMappingExists() {
        // Structural proof (§9): Day 7 has no @MessageMapping client-command handler — all mutations go
        // through REST. Scans every application bean method for @MessageMapping; the count must be zero.
        int mappingCount = 0;
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = applicationContext.getType(beanName);
            } catch (Throwable ignored) {
                continue;
            }
            if (beanType == null) {
                continue;
            }
            for (Method method : beanType.getMethods()) {
                if (method.isAnnotationPresent(MessageMapping.class)) {
                    mappingCount++;
                }
            }
        }
        assertThat(mappingCount).as("Day 7 must declare no @MessageMapping client-command handler").isZero();
    }

    private StompFrameHandler stringHandler(List<String> sink, CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                sink.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                latch.countDown();
            }
        };
    }

    private StompFrameHandler noopHandler(CountDownLatch latch) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(StompHeaders h, Object payload) { latch.countDown(); }
        };
    }
}
