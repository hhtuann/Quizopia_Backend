package com.quizopia.backend.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test-only server-side observation of every outbound STOMP message (B1R4-A1 §4/§5). Registered on the
 * {@code clientOutboundChannel} by {@link OutboundProbeConfig}, it captures what the server actually
 * sends toward clients — {@code MESSAGE} (e.g. {@code SERVER_TIME_SYNC}), {@code ERROR}, etc. — with the
 * STOMP command, {@code simpSessionId}, destination, and payload.
 *
 * <p>This is the executable observation the spec requires for <b>zero-message</b> cases (CONNECT-only /
 * topic-only / unauthorized / exactly-one) where a client-side handler cannot prove a message was never
 * sent, and for <b>ERROR no-leak</b> assertions (the captured ERROR payload/headers must not contain a
 * raw token, exception class, SQL, session/school/owner/permission, or internal path).
 *
 * <p>{@link #expect(Predicate)} returns a future completed by the next matching outbound message —
 * race-free (waiter is registered before the already-captured scan). Positive assertions {@code .get(N)}
 * return the match; negative (zero-message) assertions assert the future <b>times out</b>. No polling,
 * no {@code Thread.sleep}.
 */
public class OutboundMessageProbe implements ChannelInterceptor {

    public record Captured(StompCommand command, String sessionId, String destination, String payload) {}

    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
    /** Test-only failure sink (B1R4-A2 §2): any observation error (predicate throw, accessor/payload
     *  parse failure) is recorded here — never silently swallowed — and asserted empty by the base
     *  {@code @AfterEach}. The outbound path is still pass-through (the message is always returned). */
    private final List<Throwable> probeFailures = new CopyOnWriteArrayList<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            Object payload = message.getPayload();
            String text = (payload instanceof byte[] b) ? new String(b, StandardCharsets.UTF_8)
                    : (payload == null ? "" : String.valueOf(payload));
            // Fold the STOMP `message` header (the ERROR reason) into the captured text so leak checks
            // cover both the frame body and the header.
            String reason = accessor.getMessage();
            if (reason != null && !reason.isBlank()) {
                text = reason + " | " + text;
            }
            Captured c = new Captured(accessor.getCommand(), accessor.getSessionId(),
                    accessor.getDestination(), text);
            captured.add(c);
            for (Waiter w : waiters) {
                if (!w.done) {
                    try {
                        if (w.predicate.test(c)) {
                            w.done = true;
                            w.future.complete(c);
                        }
                    } catch (Throwable predicateFailure) {
                        recordFailure(predicateFailure);
                    }
                }
            }
        } catch (Throwable t) {
            recordFailure(t); // observation must never break the outbound path — but never silently swallow
        }
        return message; // pass-through — pure observer
    }

    private void recordFailure(Throwable t) {
        probeFailures.add(t);
    }

    /** Test-only observation failures recorded during {@code preSend} (predicate throws, parse errors). */
    public List<Throwable> failures() {
        return List.copyOf(probeFailures);
    }

    /** Clears captured messages, pending waiters, AND the failure sink (call at test setup). */
    public void clear() {
        captured.clear();
        waiters.clear();
        probeFailures.clear();
    }

    public List<Captured> snapshot() {
        return List.copyOf(captured);
    }

    public long count(Predicate<Captured> predicate) {
        return captured.stream().filter(predicate).count();
    }

    /**
     * Returns a future completed when the next outbound message matching {@code predicate} arrives.
     * Register-first-then-scan makes it race-free: a message arriving between registration and the
     * already-captured scan is caught by one of the two checks.
     */
    public CompletableFuture<Captured> expect(Predicate<Captured> predicate) {
        CompletableFuture<Captured> future = new CompletableFuture<>();
        Waiter waiter = new Waiter(predicate, future);
        waiters.add(waiter); // register BEFORE scanning already-captured
        for (Captured c : captured) {
            if (!waiter.done && predicate.test(c)) {
                waiter.done = true;
                future.complete(c);
                break;
            }
        }
        future.whenComplete((r, e) -> waiters.remove(waiter));
        return future;
    }

    private static final class Waiter {
        final Predicate<Captured> predicate;
        final CompletableFuture<Captured> future;
        volatile boolean done;
        Waiter(Predicate<Captured> predicate, CompletableFuture<Captured> future) {
            this.predicate = predicate;
            this.future = future;
        }
    }
}
