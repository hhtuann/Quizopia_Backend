package com.quizopia.backend.monitoring.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers {@code ATTEMPT_ACTIVITY} STOMP delta notifications AFTER_COMMIT
 * (P4-A4, issue #83: "lightweight realtime notifications with REST as the
 * durable source of truth").
 *
 * <p>Destination is the EXISTING teacher topic
 * {@code /topic/exam-sessions/{sessionId}} — authorization is unchanged
 * (owner teacher + EXAM_SESSION_MONITOR in {@code StompSubscribeInterceptor});
 * no new destination is introduced, the SEND spoof guard is untouched. A
 * delivery failure is logged sanitized (class name only — messages can echo
 * payloads) and swallowed: the durable event row already committed; clients
 * reconcile via the REST timeline on reconnect.
 */
@Component
public class AttemptActivityBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(AttemptActivityBroadcaster.class);

    private final com.quizopia.backend.realtime.event.RealtimePublisher publisher;

    public AttemptActivityBroadcaster(com.quizopia.backend.realtime.event.RealtimePublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
    public void onActivityEvent(AttemptActivityRealtimeEvent event) {
        // ATTEMPT_STARTED / ATTEMPT_SUBMITTED already deliver their own
        // dedicated envelopes (+ ACTIVE_COUNT_CHANGED) via
        // RealtimeEventBroadcaster — a second ATTEMPT_ACTIVITY ping for the
        // same fact would only duplicate traffic. Every OTHER durable event
        // (client telemetry, ANSWER_ACCEPTED, ATTEMPT_RESUMED,
        // ATTEMPT_TIMED_OUT) has no dedicated envelope and pings here.
        if ("ATTEMPT_STARTED".equals(event.activityType())
                || "ATTEMPT_SUBMITTED".equals(event.activityType())) {
            return;
        }
        try {
            publisher.sendActivityToSessionTopic(event.sessionId(), event.attemptId(),
                    event.userId(), event.activityType(), event.activitySource(),
                    event.clientOccurredAt(), event.occurredAt());
        } catch (RuntimeException e) {
            log.warn("ATTEMPT_ACTIVITY delivery failed for attempt {} (durable row committed; REST is source of truth): {}",
                    event.attemptId(), e.getClass().getName());
        }
    }
}
