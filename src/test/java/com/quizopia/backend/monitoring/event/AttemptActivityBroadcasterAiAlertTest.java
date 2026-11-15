package com.quizopia.backend.monitoring.event;

import com.quizopia.backend.realtime.event.RealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-C (issue #90, CONTRACT §5): AI alert activity events ride the
 * EXISTING activity-event path — the AFTER_COMMIT broadcaster must NOT
 * skip them. The skip list exists only because ATTEMPT_STARTED /
 * ATTEMPT_SUBMITTED deliver their own dedicated envelopes; AI_ALERT_* (like
 * every other durable event) has no dedicated envelope and pings the
 * existing teacher topic, where the teacher feed refetches the durable
 * REST backfill.
 */
class AttemptActivityBroadcasterAiAlertTest {

    private static final class RecordingPublisher extends RealtimePublisher {

        final List<String> delivered = new ArrayList<>();

        RecordingPublisher() {
            super(null, null); // never used — sendActivityToSessionTopic overridden
        }

        @Override
        public void sendActivityToSessionTopic(Long sessionId, Long attemptId, Long userId,
                String activityType, String activitySource, Instant clientOccurredAt,
                Instant occurredAt) {
            delivered.add(activityType);
        }
    }

    @Test
    void aiAlertEventsAreDeliveredNotSkipped() {
        RecordingPublisher publisher = new RecordingPublisher();
        AttemptActivityBroadcaster broadcaster = new AttemptActivityBroadcaster(publisher);

        broadcaster.onActivityEvent(new AttemptActivityRealtimeEvent(
                7L, 11L, 21L, "AI_ALERT_RAISED", "SERVER", null, Instant.now()));
        broadcaster.onActivityEvent(new AttemptActivityRealtimeEvent(
                7L, 11L, 21L, "AI_ALERT_UPDATED", "SERVER", null, Instant.now()));

        assertThat(publisher.delivered)
                .as("AI alert events reach the existing teacher topic ping")
                .containsExactly("AI_ALERT_RAISED", "AI_ALERT_UPDATED");
    }

    @Test
    void lifecycleEventsWithDedicatedEnvelopesStaySkipped() {
        RecordingPublisher publisher = new RecordingPublisher();
        AttemptActivityBroadcaster broadcaster = new AttemptActivityBroadcaster(publisher);

        broadcaster.onActivityEvent(new AttemptActivityRealtimeEvent(
                7L, 11L, 21L, "ATTEMPT_STARTED", "SERVER", null, Instant.now()));
        broadcaster.onActivityEvent(new AttemptActivityRealtimeEvent(
                7L, 11L, 21L, "ATTEMPT_SUBMITTED", "SERVER", null, Instant.now()));

        assertThat(publisher.delivered)
                .as("the dedicated-envelope events keep their exact no-ping semantics")
                .isEmpty();
    }
}
