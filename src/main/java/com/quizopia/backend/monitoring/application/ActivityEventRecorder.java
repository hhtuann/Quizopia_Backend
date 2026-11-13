package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.event.AttemptActivityRealtimeEvent;
import com.quizopia.backend.monitoring.repository.AttemptActivityEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records SERVER-confirmed activity events INSIDE the caller's transaction
 * (P4-A2/#81, P4-A4/#83): the durable row commits or rolls back with the fact
 * it records, and the STOMP delta notification fires only AFTER_COMMIT.
 *
 * <p>Used by {@code AttemptService} (ATTEMPT_STARTED / ATTEMPT_RESUMED),
 * {@code AttemptSubmitService} (ATTEMPT_SUBMITTED / ATTEMPT_TIMED_OUT) and
 * {@code AttemptAutosaveService} (ANSWER_ACCEPTED). Lock-order note: the
 * insert touches only the append-only {@code attempt_activity_events} table
 * (no row locks conflict with the frozen session→attempt matrix).
 *
 * <p>Caller MUST already hold the attempt's authorization context — this
 * recorder performs no permission checks of its own (it is internal
 * infrastructure, never exposed via a controller).
 */
@Service
public class ActivityEventRecorder {

    private final AttemptActivityEventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ActivityEventRecorder(AttemptActivityEventRepository eventRepository,
            ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Appends one server-confirmed event. Joins the caller's transaction
     * ({@code MANDATORY} — an event recorded outside the fact's transaction
     * would violate the atomicity contract).
     *
     * @param occurredAt the business instant of the fact (post-lock server
     *                   clock); stored as server_received_at
     * @param metadataJson small JSON object (e.g.
     *                     {"attemptQuestionId":1,"acceptedSequence":4}); NEVER
     *                     answer content, tokens or secrets
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordServerEvent(Long attemptId, Long sessionId, Long userId,
            ActivityEventType type, Instant occurredAt, String metadataJson) {
        eventRepository.insertServerEvent(UUID.randomUUID(), attemptId, type.name(),
                occurredAt == null ? null : java.sql.Timestamp.from(occurredAt),
                metadataJson == null ? "{}" : metadataJson);
        eventPublisher.publishEvent(new AttemptActivityRealtimeEvent(
                sessionId, attemptId, userId, type.name(), "SERVER", null, occurredAt));
    }
}
