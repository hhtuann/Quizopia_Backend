package com.quizopia.backend.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Context-free unit tests for {@link OutboundMessageProbe} integrity and race-freedom (B1R4-A2 §2,
 * B1R4-B2F6). Proves:
 * <ul>
 *   <li>pass-through + capture;</li>
 *   <li>predicate failure recorded;</li>
 *   <li>event-before-await: message recorded before expect() → scan finds it immediately;</li>
 *   <li>waiter-before-event: expect() armed before message arrives → waiter completes;</li>
 *   <li>non-matching then matching: waiter ignores non-matching, completes on matching;</li>
 *   <li>clear isolation: old messages don't satisfy new waiters;</li>
 *   <li>session-scoped predicates: different simpSessionIds don't cross-satisfy.</li>
 * </ul>
 */
class OutboundMessageProbeTest {

    @Test
    void preSendIsPassThroughAndCaptures() {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        Message<byte[]> msg = frame(StompCommand.MESSAGE, "/topic/x", "hello", "sess1");
        Message<?> returned = probe.preSend(msg, null);
        assertThat(returned).isSameAs(msg);
        assertThat(probe.snapshot()).hasSize(1);
        assertThat(probe.failures()).isEmpty();
    }

    @Test
    void predicateFailureIsRecordedNotSwallowed() {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        probe.expect(c -> { throw new RuntimeException("boom"); });
        Message<byte[]> msg = frame(StompCommand.MESSAGE, "/topic/x", "payload", "sess1");
        Message<?> returned = probe.preSend(msg, null);
        assertThat(returned).isSameAs(msg);
        assertThat(probe.failures()).hasSize(1);
        assertThat(probe.failures().get(0)).hasMessageContaining("boom");
        assertThat(probe.snapshot()).hasSize(1);
    }

    @Test
    void clearResetsFailuresAndCaptured() {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        probe.expect(c -> { throw new RuntimeException("boom"); });
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "p", "sess1"), null);
        probe.clear();
        assertThat(probe.snapshot()).isEmpty();
        assertThat(probe.failures()).isEmpty();
    }

    // === B1R4-B2F6 deterministic race-freedom tests ===

    @Test
    void eventBeforeAwait_returnsImmediately() throws Exception {
        // Message recorded BEFORE expect() → scan finds it in captured.
        OutboundMessageProbe probe = new OutboundMessageProbe();
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "SERVER_TIME_SYNC", "sess1"), null);
        OutboundMessageProbe.Captured result = probe.expect(payloadContains("SERVER_TIME_SYNC"))
                .get(1, TimeUnit.SECONDS);
        assertThat(result.payload()).contains("SERVER_TIME_SYNC");
    }

    @Test
    void waiterBeforeEvent_completesOnArrival() throws Exception {
        // Waiter armed BEFORE the message arrives → waiter completes when preSend fires.
        OutboundMessageProbe probe = new OutboundMessageProbe();
        CompletableFuture<OutboundMessageProbe.Captured> future =
                probe.expect(payloadContains("SERVER_TIME_SYNC"));
        assertThat(future).isNotDone();
        probe.preSend(frame(StompCommand.MESSAGE, "/user/queue/attempt", "SERVER_TIME_SYNC", "sess1"), null);
        OutboundMessageProbe.Captured result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.payload()).contains("SERVER_TIME_SYNC");
    }

    @Test
    void nonmatchingThenMatching_waiterCompletesOnCorrectMessage() throws Exception {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        CompletableFuture<OutboundMessageProbe.Captured> future =
                probe.expect(payloadContains("TARGET"));
        // Non-matching message arrives first — waiter must NOT complete.
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "OTHER", "sess1"), null);
        assertThat(future).isNotDone();
        // Matching message arrives — waiter completes.
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "TARGET", "sess1"), null);
        OutboundMessageProbe.Captured result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.payload()).contains("TARGET");
    }

    @Test
    void clearIsolation_oldMessagesDoNotSatisfyNewWaiters() throws Exception {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "OLD", "sess1"), null);
        probe.clear();
        // Arm a waiter AFTER clear — old message must NOT satisfy it.
        CompletableFuture<OutboundMessageProbe.Captured> future =
                probe.expect(payloadContains("OLD"));
        assertThat(future).isNotDone();
        // New matching message satisfies the waiter.
        probe.preSend(frame(StompCommand.MESSAGE, "/topic/x", "OLD", "sess1"), null);
        OutboundMessageProbe.Captured result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.payload()).contains("OLD");
    }

    @Test
    void sessionScopedPredicates_doNotCrossSatisfy() throws Exception {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        // Two waiters scoped to different simpSessionIds.
        CompletableFuture<OutboundMessageProbe.Captured> waiterA =
                probe.expect(c -> "sessA".equals(c.sessionId()) && c.payload().contains("SYNC"));
        CompletableFuture<OutboundMessageProbe.Captured> waiterB =
                probe.expect(c -> "sessB".equals(c.sessionId()) && c.payload().contains("SYNC"));
        // Message for sessB arrives — must complete waiterB, NOT waiterA.
        probe.preSend(frame(StompCommand.MESSAGE, "/user/queue/attempt", "SYNC-body", "sessB"), null);
        assertThat(waiterB).isDone();
        assertThat(waiterA).isNotDone();
        // Message for sessA arrives — completes waiterA.
        probe.preSend(frame(StompCommand.MESSAGE, "/user/queue/attempt", "SYNC-body", "sessA"), null);
        assertThat(waiterA.get(1, TimeUnit.SECONDS).sessionId()).isEqualTo("sessA");
    }

    @Test
    void expectTimesOutWhenNoMatch() {
        OutboundMessageProbe probe = new OutboundMessageProbe();
        CompletableFuture<OutboundMessageProbe.Captured> future =
                probe.expect(payloadContains("NONEXISTENT"));
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    // --- helpers ---

    private static java.util.function.Predicate<OutboundMessageProbe.Captured> payloadContains(String needle) {
        return c -> c.payload() != null && c.payload().contains(needle);
    }

    private Message<byte[]> frame(StompCommand command, String destination, String body, String simpSessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId(simpSessionId);
        accessor.setMessage("reason-" + body);
        return MessageBuilder.createMessage(body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                accessor.getMessageHeaders());
    }
}
