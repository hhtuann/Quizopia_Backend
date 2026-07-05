package com.hhtuann.backend.realtime.support;

import com.hhtuann.backend.attempt.repository.AttemptRepository;
import com.hhtuann.backend.realtime.event.RealtimeActiveCountService;
import com.hhtuann.backend.realtime.event.RealtimePublisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;

/**
 * Test-only failure-injection + recording decorators (B1R4-B §14). Provides {@code @Primary} beans that
 * wrap the production {@link RealtimePublisher} and {@link RealtimeActiveCountService} so the
 * {@code RealtimeEventBroadcaster} (and {@code ServerTimeSyncListener}) receive the decorators when a
 * test {@code @Import}s this configuration. Tests that do NOT import it get the real beans unchanged —
 * no global mutation, no test-order dependence.
 *
 * <ul>
 *   <li>{@link FailingRealtimePublisher} — one-shot send failure by (type, sessionId) + send-order log.</li>
 *   <li>{@link RecordingRealtimeActiveCountService} — invocation count + REQUIRES_NEW tx context +
 *       one-shot count-query failure.</li>
 * </ul>
 */
@TestConfiguration
public class RealtimeTestSupportConfig {

    @Bean
    @Primary
    RealtimePublisher failingRealtimePublisher(SimpMessagingTemplate messaging, Clock clock) {
        return new FailingRealtimePublisher(messaging, clock);
    }

    @Bean
    @Primary
    RealtimeActiveCountService recordingRealtimeActiveCountService(AttemptRepository attemptRepository,
                                                                   javax.sql.DataSource dataSource) {
        RecordingRealtimeActiveCountService bean = new RecordingRealtimeActiveCountService(attemptRepository);
        bean.bindDataSource(dataSource); // same key Spring's tx manager uses for ConnectionHolder lookup
        return bean;
    }
}
