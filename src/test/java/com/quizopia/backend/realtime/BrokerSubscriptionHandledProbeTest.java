package com.quizopia.backend.realtime;

import com.quizopia.backend.realtime.support.BrokerSubscribeHoldProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2F8 §3/§6.2 — deterministic proof of the subscription-registration race and the B2F8 fix.
 *
 * <p>Holds {@code SimpleBrokerMessageHandler} on the {@code brokerChannel} just before it registers the
 * personal-queue subscription, and proves:
 * <ol>
 *   <li>{@code SessionSubscribeEvent} has ALREADY fired (awaitAccepted returns) while the broker registration
 *       is still held incomplete — i.e. the race window exists (so emitting on the event, as B2F6/B2F7 did,
 *       would send the SERVER_TIME_SYNC into a subscription that doesn't exist yet);</li>
 *   <li>under the B2F8 fix, NO SERVER_TIME_SYNC is emitted while the broker is held (emission is gated on
 *       the broker's afterMessageHandled — registration completion);</li>
 *   <li>after release, exactly one SERVER_TIME_SYNC is emitted and delivered to the client.</li>
 * </ol>
 * No {@code Thread.sleep}; the hold is a bounded latch released by the test, awaitEntered is a bounded future.
 */
class BrokerSubscriptionHandledProbeTest extends RealtimeStompTestBase {

    @Autowired com.quizopia.backend.realtime.support.ChannelFlowProbeRegistrar flowProbeRegistrar;

    private BrokerSubscribeHoldProbe holdProbe() {
        return flowProbeRegistrar.brokerHoldProbe();
    }

    @AfterEach
    void disarmHold() {
        holdProbe().disarm();
    }

    @Test
    void syncEmitsOnlyAfterBrokerSubscriptionCompletes() throws Exception {
        clock.setInstant(Instant.parse("2026-07-05T08:00:00Z"));
        String tag = UUID.randomUUID().toString().substring(0, 6);
        long uid = insertUserWithRole("race-" + tag, "STUDENT");
        String token = accessToken(uid, "race", List.of("STUDENT"));

        outboundProbe.clear();
        acceptedProbe.clear();
        holdProbe().arm();

        try (StompTestConnection conn = connect(token)) {
            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            // Arm the outbound waiter before subscribing (probe cleared above).
            var outboundFuture = outboundProbe.expect(serverTimeSyncMessage());
            conn.subscribe("/user/queue/attempt", stringHandler(received, latch));

            // (1) SessionSubscribeEvent fires WHILE the broker is held — the race window is real.
            awaitAccepted(acceptedDestination("/user/queue/attempt"), 10);
            // (2) The broker handler has entered and is blocked before registration completes; no sync yet.
            holdProbe().awaitEntered(10);
            assertNoOutbound(serverTimeSyncMessage(), 2);
            assertThat(latch.await(1, TimeUnit.SECONDS))
                    .as("no client delivery while broker subscription registration is held")
                    .isFalse();

            // (3) Release the broker; registration completes → afterMessageHandled → one sync → delivered.
            holdProbe().release();
            outboundFuture.get(15, TimeUnit.SECONDS);
            assertThat(latch.await(15, TimeUnit.SECONDS))
                    .as("SERVER_TIME_SYNC delivered once the broker registration completes")
                    .isTrue();
            assertThat(received).hasSize(1);
            assertThat(received.get(0)).contains("SERVER_TIME_SYNC");
        }
    }

    private org.springframework.messaging.simp.stomp.StompFrameHandler stringHandler(List<String> sink, CountDownLatch latch) {
        return new org.springframework.messaging.simp.stomp.StompFrameHandler() {
            @Override public java.lang.reflect.Type getPayloadType(org.springframework.messaging.simp.stomp.StompHeaders h) { return byte[].class; }
            @Override public void handleFrame(org.springframework.messaging.simp.stomp.StompHeaders h, Object payload) {
                sink.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                latch.countDown();
            }
        };
    }
}
