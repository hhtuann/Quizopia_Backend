package com.quizopia.backend.realtime;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import com.quizopia.backend.realtime.security.RealtimeSessionRegistry;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import static org.assertj.core.api.Assertions.assertThat;

/** Real broker/client regression, not a preSend observation masquerading as delivery evidence. */
class WebSocketRevocationIntegrationTests extends RealtimeStompTestBase {
    @Autowired SimpMessagingTemplate messaging;
    @Autowired @Qualifier("clientOutboundChannel") ExecutorSubscribableChannel outbound;
    @Autowired RealtimeSessionRegistry sessions;

    @ParameterizedTest
    @CsvSource({"false,epoch", "true,epoch", "false,status", "true,status", "false,expiry", "true,expiry"})
    void committedRevocationDropsQueuedAndNewDelivery(boolean teacher, String revocation) throws Exception {
        clock.setInstant(Instant.now());
        String tag = "rev-" + UUID.randomUUID().toString().substring(0, 8);
        long examSession = teacher ? createTeacherOwnedOpenSession(tag) : 0;
        long user = teacher ? teacherIdForSession(examSession) : insertUserWithRole(tag, "STUDENT");
        String destination = teacher ? "/topic/exam-sessions/" + examSession : "/user/queue/attempt";
        DeliveryGate gate = new DeliveryGate();
        List<ChannelInterceptor> original = new ArrayList<>(outbound.getInterceptors());
        List<ChannelInterceptor> gated = new ArrayList<>();
        gated.add(gate);
        gated.addAll(original);
        // setInterceptors rebuilds BOTH the send and executor interceptor lists in this order.
        outbound.setInterceptors(gated);
        try (StompTestConnection old = connect(token(user, 0))) {
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            old.subscribe(destination, handler(received));
            awaitAccepted(acceptedDestination(destination), 15);
            proveDelivery(user, destination, teacher, received, "ready");
            gate.armed = true;
            publish(user, destination, teacher, "queued-before-commit");
            assertThat(gate.entered.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sessions.isCurrent(gate.sessionId)).isTrue();
            if (revocation.equals("epoch")) {
                jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", user);
            } else if (revocation.equals("status")) {
                jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", user);
            } else {
                clock.setInstant(Instant.now().plusSeconds(601));
            }
            assertThat(sessions.isCurrent(gate.sessionId)).isFalse();
            gate.release.countDown();
            assertThat(gate.completed.await(15, TimeUnit.SECONDS)).isTrue();
            publish(user, destination, teacher, "new-after-commit");
            assertNoProtectedDelivery(received, 2);
            // A genuinely fresh CONNECT restores both topic and personal delivery, not the old session.
            if (revocation.equals("status")) jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user);
            if (!revocation.equals("epoch")) {
                // Keep the original connection revoked while restoring the account for a fresh login.
                jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", user);
                clock.setInstant(Instant.now());
            }
            try (StompTestConnection fresh = connect(token(user, 1))) {
                LinkedBlockingQueue<String> freshReceived = new LinkedBlockingQueue<>();
                fresh.subscribe(destination, handler(freshReceived));
                proveDelivery(user, destination, teacher, freshReceived, "fresh");
                assertNoProtectedDelivery(received, 1);
            }
            acceptedProbe.clear();
            old.subscribe(destination, handler(received));
            assertThat(old.awaitError(15)).doesNotContain("token_version");
            old.awaitDisconnect();
            assertThat(sessions.isCurrent(gate.sessionId)).isFalse();
            assertNoAccepted(acceptedDestination(destination), 1);
        } finally {
            gate.release.countDown();
            outbound.setInterceptors(original);
            clock.setInstant(Instant.now());
        }
    }

    @Test
    void replacingInterceptorsRemovesExecutorCallbacksBeforeSecondInvocation() {
        var channel = new ExecutorSubscribableChannel();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        var gate = new ExecutorChannelInterceptor() {
            @Override public Message<?> beforeHandle(Message<?> message, MessageChannel ignored, MessageHandler handler) {
                gateCalls.incrementAndGet();
                return message;
            }
        };
        channel.subscribe(message -> deliveries.incrementAndGet());
        channel.setInterceptors(List.of(gate));
        channel.send(MessageBuilder.withPayload("first").build());
        channel.setInterceptors(List.of());
        channel.send(MessageBuilder.withPayload("second").build());
        assertThat(gateCalls).hasValue(1);
        assertThat(deliveries).hasValue(2);
    }

    private String token(long user, int epoch) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(securityProperties.getJwt().getIssuer())
                .audience(List.of(securityProperties.getJwt().getAudience())).subject(Long.toString(user))
                .claim("token_version", epoch).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void publish(long user, String destination, boolean teacher, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (teacher) messaging.convertAndSend(destination, bytes);
        else messaging.convertAndSendToUser(Long.toString(user), "/queue/attempt", bytes);
    }

    private void proveDelivery(long user, String destination, boolean teacher,
                               LinkedBlockingQueue<String> received, String marker) throws Exception {
        // SessionSubscribeEvent precedes async broker registration. Retry a harmless marker until the
        // real client proves registration, rather than relying on sleeps or an inbound-only probe.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            publish(user, destination, teacher, marker);
            String value = received.poll(100, TimeUnit.MILLISECONDS);
            if (marker.equals(value)) return;
        }
        throw new AssertionError("Broker subscription did not deliver readiness marker");
    }

    private static StompFrameHandler handler(LinkedBlockingQueue<String> received) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) {
                String value = new String((byte[]) payload, StandardCharsets.UTF_8);
                if (!value.contains("SERVER_TIME_SYNC")) received.add(value);
            }
        };
    }

    private void assertNoProtectedDelivery(LinkedBlockingQueue<String> received, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String value = received.poll(100, TimeUnit.MILLISECONDS);
            // Readiness retries may already be in flight when the gate is armed.
            assertThat(value).isNotIn("queued-before-commit", "new-after-commit", "fresh");
        }
    }

    private static class DeliveryGate implements ExecutorChannelInterceptor {
        volatile boolean armed;
        volatile String sessionId;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
            if (message.getPayload() instanceof byte[] bytes
                    && "queued-before-commit".equals(new String(bytes, StandardCharsets.UTF_8))) {
                completed.countDown();
            }
        }

        @Override
        public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
            if (armed && message.getPayload() instanceof byte[] bytes
                    && "queued-before-commit".equals(new String(bytes, StandardCharsets.UTF_8))) {
                sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
                entered.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Delivery gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return message;
        }
    }
}
