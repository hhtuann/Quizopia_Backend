package com.hhtuann.backend.realtime.support;

import com.hhtuann.backend.realtime.event.RealtimeEventType;
import com.hhtuann.backend.realtime.event.RealtimePublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

/**
 * Test-only decorator (B1R4-B §14) over {@link RealtimePublisher}, installed as a {@code @Primary} bean
 * by {@link RealtimeTestSupportConfig}. It delegates to the real publisher but can be configured to
 * throw on the next send matching a {@code (eventType, sessionId)} predicate — one-shot, so the FIRST
 * matching send fails and subsequent sends succeed (proves failure isolation: a failed first send does
 * not suppress the second). Records every send attempt (type + sessionId) for order/count assertions.
 *
 * <p>The injected exception carries a sensitive marker (token / SQL / email / answerKey) used by the
 * §17 sanitized-logging assertion — the marker must NOT appear in the broadcaster's WARN log.
 *
 * <p>Never alters production routing on failure: it throws, the broadcaster's {@code sendSafe} catches
 * it, logs sanitized, and continues — REST stays committed. No retry, no duplicate.
 */
public class FailingRealtimePublisher extends RealtimePublisher {

    public record SendAttempt(RealtimeEventType type, Long sessionId, boolean failed) {}

    /** A sensitive marker baked into the injected failure message; the sanitized-log test asserts it
     *  never reaches the broadcaster's WARN output. */
    public static final String SENSITIVE_MARKER =
            "Bearer secret-token SELECT * FROM attempts student@example.com answerKey=A";

    private final List<SendAttempt> sendAttempts = new CopyOnWriteArrayList<>();
    private volatile BiPredicate<RealtimeEventType, Long> failPredicate;

    public FailingRealtimePublisher(SimpMessagingTemplate messaging, Clock clock) {
        super(messaging, clock);
    }

    /** Configure a one-shot failure: the next send matching the predicate throws, later sends pass. */
    public void failNext(BiPredicate<RealtimeEventType, Long> predicate) {
        this.failPredicate = predicate;
    }

    public List<SendAttempt> sendAttempts() {
        return List.copyOf(sendAttempts);
    }

    public void reset() {
        sendAttempts.clear();
        failPredicate = null;
    }

    @Override
    public void sendToSessionTopic(Long sessionId, RealtimeEventType type, Long attemptId,
                                   Long studentProfileId, Instant occurredAt, Long activeCount) {
        boolean shouldFail = false;
        BiPredicate<RealtimeEventType, Long> p = failPredicate;
        if (p != null && p.test(type, sessionId)) {
            shouldFail = true;
            failPredicate = null; // one-shot — subsequent sends succeed (failure isolation)
        }
        sendAttempts.add(new SendAttempt(type, sessionId, shouldFail));
        if (shouldFail) {
            // The marker proves the broadcaster must sanitize — it must NOT leak to logs/REST.
            throw new RuntimeException("test-injected outbound failure for " + type
                    + " :: " + SENSITIVE_MARKER);
        }
        super.sendToSessionTopic(sessionId, type, attemptId, studentProfileId, occurredAt, activeCount);
    }
}
