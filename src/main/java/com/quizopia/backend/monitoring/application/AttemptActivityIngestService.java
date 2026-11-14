package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchRequest;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse.ItemResult;
import com.quizopia.backend.monitoring.dto.ActivityEventBatchResponse.Status;
import com.quizopia.backend.monitoring.event.AttemptActivityRealtimeEvent;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.AttemptActivityEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingest application service for
 * {@code POST /api/attempts/{attemptId}/activity-events} (P4-A2, issue #81).
 *
 * <p><b>Best-effort, ownership-bounded telemetry:</b> events are
 * observations, never verdicts (ADR-008 invariant 8). The security boundary
 * is authentication + attempt ownership; the attempt row is NOT locked and
 * ingest never contends with autosave/submit (telemetry is lower priority
 * than Autosave by design). Events may arrive for IN_PROGRESS, SUBMITTED or
 * GRADED attempts — a final telemetry flush right after submit/reload is a
 * legitimate real-world pattern and grading is unaffected by append-only
 * rows.
 *
 * <p><b>Order of operations:</b> permission check → per-user rate admission
 * (own REQUIRES_NEW tx, leaf-most lock) → attempt load + ownership → batch
 * validation (atomic: any invalid event rejects the whole batch BEFORE any
 * insert) → per-event idempotent insert
 * ({@code ON CONFLICT (event_id) DO NOTHING}) → AFTER_COMMIT STOMP delta
 * notification per ACCEPTED event (duplicates never re-notify).
 *
 * <p><b>Client clock posture:</b> {@code clientOccurredAt} is stored
 * VERBATIM; only a wide sanity window (2000-01-01 … 2200-01-01, server
 * clock) rejects garbage. The durable chronological order is always the
 * server {@code server_received_at}, never the client timestamp.
 */
@Service
public class AttemptActivityIngestService {

    /** Sanity window for client timestamps (server clock domain). */
    private static final Instant CLIENT_TS_MIN = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant CLIENT_TS_MAX = Instant.parse("2200-01-01T00:00:00Z");

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final AttemptActivityEventRepository eventRepo;
    private final ActivityIngestRateLimiter rateLimiter;
    private final ActivityIngestProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public AttemptActivityIngestService(AttemptAuthorizationService auth,
            AttemptRepository attemptRepo,
            AttemptActivityEventRepository eventRepo,
            ActivityIngestRateLimiter rateLimiter,
            ActivityIngestProperties properties,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.eventRepo = eventRepo;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ActivityEventBatchResponse ingest(Long userId, Long attemptId, ActivityEventBatchRequest request) {
        auth.requireLearnerWithPermission(userId, "ATTEMPT_ACTIVITY_INGEST");

        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "events list must not be empty");
        }
        if (request.events().size() > properties.getMaxBatchSize()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_BATCH_TOO_LARGE);
        }

        // Rate admission AFTER payload shape check (a garbage request never
        // burns a window slot) but BEFORE any DB read of the attempt.
        rateLimiter.admitUser(userId);

        // Ownership: 404 for a missing attempt, 403 for a foreign one —
        // the caller is writing to it (autosave convention).
        Attempt attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (!Objects.equals(attempt.getUserId(), userId)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }

        // Atomic validation: every event must be well-formed BEFORE any insert.
        List<ValidatedEvent> validated = new ArrayList<>(request.events().size());
        for (ActivityEventBatchRequest.Item item : request.events()) {
            validated.add(validate(item));
        }

        List<ItemResult> results = new ArrayList<>(validated.size());
        int accepted = 0;
        for (ValidatedEvent event : validated) {
            int inserted = eventRepo.insertClientEventIfAbsent(
                    event.eventId(), attemptId, event.type().name(),
                    Timestamp.from(event.clientOccurredAt()), event.clientSequence(),
                    event.clientInstanceId(), event.metadataJson());
            boolean isAccepted = inserted == 1;
            if (isAccepted) {
                accepted++;
                // AFTER_COMMIT teacher-topic delta ping (no metadata on the wire).
                eventPublisher.publishEvent(new AttemptActivityRealtimeEvent(
                        attempt.getExamSessionId(), attemptId, userId,
                        event.type().name(), "CLIENT", event.clientOccurredAt(),
                        Instant.now()));
            }
            results.add(ItemResult.of(event.eventId(),
                    isAccepted ? Status.ACCEPTED : Status.DUPLICATE));
        }
        return new ActivityEventBatchResponse(accepted, results.size() - accepted, results);
    }

    private ValidatedEvent validate(ActivityEventBatchRequest.Item item) {
        if (item == null || item.eventId() == null) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "eventId is required");
        }
        ActivityEventType type = ActivityEventType.parse(item.eventType());
        if (type == null || !type.isClientType()) {
            // Server-confirmed types (ATTEMPT_*, ANSWER_ACCEPTED) are NEVER
            // accepted from a client — a browser cannot forge provenance.
            throw new MonitoringException(MonitoringErrorCode.MONITORING_EVENT_TYPE_REJECTED);
        }
        if (item.clientOccurredAt() == null
                || item.clientOccurredAt().isBefore(CLIENT_TS_MIN)
                || item.clientOccurredAt().isAfter(CLIENT_TS_MAX)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "clientOccurredAt outside the accepted sanity window");
        }
        if (item.clientSequence() == null || item.clientSequence() < 0) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "clientSequence must be >= 0");
        }
        String metadataJson = "{}";
        if (item.metadata() != null && !item.metadata().isNull()) {
            if (!item.metadata().isObject()) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                        "metadata must be a JSON object");
            }
            metadataJson = objectMapper.writeValueAsString(item.metadata());
            if (metadataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > properties.getMaxMetadataBytes()) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_METADATA_TOO_LARGE);
            }
        }
        return new ValidatedEvent(item.eventId(), type, item.clientOccurredAt(),
                item.clientSequence(), item.clientInstanceId(), metadataJson);
    }

    private record ValidatedEvent(
            UUID eventId,
            ActivityEventType type,
            Instant clientOccurredAt,
            long clientSequence,
            UUID clientInstanceId,
            String metadataJson) {
    }
}
