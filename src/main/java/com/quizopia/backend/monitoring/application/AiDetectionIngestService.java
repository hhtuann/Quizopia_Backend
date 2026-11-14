package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.monitoring.domain.model.AiDetectionType;
import com.quizopia.backend.monitoring.domain.model.AiProvenance;
import com.quizopia.backend.monitoring.domain.model.MonitoringMediaBinding;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchRequest;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse.ItemResult;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse.Status;
import com.quizopia.backend.monitoring.dto.AiWorkerDetectionBatchRequest;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringAiDetectionRepository;
import com.quizopia.backend.monitoring.repository.MonitoringMediaBindingRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ingest application service for AI detections (P4-C, issues #88/#90,
 * ADR-008 amendment) — both transport paths of CONTRACT §3 share this
 * core:
 *
 * <ul>
 *   <li>{@code POST /api/attempts/{attemptId}/ai-detections} — student
 *       JWT ({@link #ingestStudent}); provenance CLIENT_MODEL;</li>
 *   <li>{@code POST /internal/ai/v1/detections} — worker X-Api-Key
 *       ({@link #ingestWorker}); provenance WORKER_VERIFIED; items carry
 *       attemptId+userId explicitly.</li>
 * </ul>
 *
 * <p><b>Order of operations (student path):</b> permission
 * ({@code AI_DETECTION_INGEST}) → batch shape → rate admission (own
 * REQUIRES_NEW leaf tx, domain {@code monitoring:ai-ingest}) → attempt
 * load + ownership (404/403) → ATOMIC validation of every item (one
 * invalid item rejects the whole batch BEFORE any insert) → per-item
 * time binding + media binding (CONTRACT §3.2) → idempotent insert
 * ({@code ON CONFLICT (event_id) DO NOTHING}) → aggregation (CONTRACT
 * §5) in the SAME transaction → AFTER_COMMIT STOMP ping via the existing
 * activity-event machinery.
 *
 * <p><b>Time binding &amp; quarantine (CONTRACT §3.2):</b>
 * <ul>
 *   <li>attempt not IN_PROGRESS → every item stored
 *       {@code QUARANTINED_LATE} (reason {@code ATTEMPT_NOT_IN_PROGRESS}),
 *       never aggregates, never alerts;</li>
 *   <li>no ACTIVE media binding for (attempt, user) in
 *       {@code monitoring_media_bindings} → item {@code REJECTED} with
 *       reason {@code NO_ACTIVE_MEDIA} (NOT stored) — detections are only
 *       accepted while tied to the media identity that published the
 *       camera;</li>
 *   <li>{@code observedAt} outside {@code [attempt.startedAt − 5s,
 *       now + 5s]} → stored {@code QUARANTINED_LATE} (reason
 *       {@code OBSERVED_AT_OUT_OF_WINDOW}, lateMs evidence) — late or
 *       out-of-order events bind the ORIGINAL attempt but never alert;</li>
 *   <li>duplicate {@code eventId} → {@code DUPLICATE}, never re-alerts.</li>
 * </ul>
 *
 * <p><b>Honesty (CONTRACT §8):</b> detections are model observations; the
 * client clock is stored VERBATIM (only the 2000..2200 sanity window
 * rejects garbage at validation time); nothing here touches grades.
 */
@Service
public class AiDetectionIngestService {

    /** Sanity window for client timestamps (server clock domain). */
    private static final Instant OBSERVED_TS_MIN = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant OBSERVED_TS_MAX = Instant.parse("2200-01-01T00:00:00Z");

    /** Time-binding slack around the attempt window (CONTRACT §3.2). */
    private static final Duration TIME_BIND_SLACK = Duration.ofSeconds(5);

    /** Rate-bucket domain of this ingest path (own buckets, own limit). */
    static final String RATE_DOMAIN = "monitoring:ai-ingest";

    static final String QUARANTINE_ATTEMPT_NOT_IN_PROGRESS = "ATTEMPT_NOT_IN_PROGRESS";
    static final String QUARANTINE_OBSERVED_AT_OUT_OF_WINDOW = "OBSERVED_AT_OUT_OF_WINDOW";

    /** Runtimes the browser paths may claim (worker claims WORKER_ORT only). */
    private static final Set<String> CLIENT_RUNTIMES = Set.of("ORT_WEB_WASM", "ORT_WEB_WEBGPU");
    private static final String WORKER_RUNTIME = "WORKER_ORT";

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final MonitoringAiDetectionRepository detectionRepo;
    private final MonitoringMediaBindingRepository bindingRepo;
    private final AiAlertAggregationService aggregationService;
    private final ActivityIngestRateLimiter rateLimiter;
    private final AiIngestProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiDetectionIngestService(AttemptAuthorizationService auth,
            AttemptRepository attemptRepo,
            MonitoringAiDetectionRepository detectionRepo,
            MonitoringMediaBindingRepository bindingRepo,
            AiAlertAggregationService aggregationService,
            ActivityIngestRateLimiter rateLimiter,
            AiIngestProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.detectionRepo = detectionRepo;
        this.bindingRepo = bindingRepo;
        this.aggregationService = aggregationService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ==================================================================
    // Student path — POST /api/attempts/{attemptId}/ai-detections
    // ==================================================================

    @Transactional
    public AiDetectionBatchResponse ingestStudent(Long userId, Long attemptId,
            AiDetectionBatchRequest request) {
        auth.requireLearnerWithPermission(userId, "AI_DETECTION_INGEST");

        requireBatchShape(request == null ? null : request.detections());

        // Rate admission AFTER payload shape check (a garbage request never
        // burns a window slot) but BEFORE any DB read of the attempt.
        rateLimiter.admit(RATE_DOMAIN, userId, properties.getRateLimit(), properties.getWindow());

        // Ownership: 404 for a missing attempt, 403 for a foreign one.
        Attempt attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (!Objects.equals(attempt.getUserId(), userId)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }

        // Atomic validation: every item must be well-formed BEFORE any insert.
        List<ValidatedDetection> validated = new ArrayList<>(request.detections().size());
        for (AiDetectionBatchRequest.Item item : request.detections()) {
            validated.add(validate(item, CLIENT_RUNTIMES));
        }
        return process(attempt, validated, AiProvenance.CLIENT_MODEL);
    }

    // ==================================================================
    // Worker path — POST /internal/ai/v1/detections (X-Api-Key in the
    // controller; no student JWT, items carry attemptId+userId).
    // ==================================================================

    @Transactional
    public AiDetectionBatchResponse ingestWorker(AiWorkerDetectionBatchRequest request) {
        requireBatchShape(request == null ? null : request.detections());

        List<ItemResult> results = new ArrayList<>(request.detections().size());
        int accepted = 0;
        int duplicates = 0;
        int quarantined = 0;
        int rejected = 0;

        // Per-item attempt resolution: a worker batch may span attempts,
        // so a bad attemptId/ownership is an ITEM outcome, never a 404.
        Map<Long, Attempt> attempts = new HashMap<>();
        for (AiWorkerDetectionBatchRequest.Item item : request.detections()) {
            if (item == null || item.attemptId() == null || item.userId() == null
                    || item.detection() == null) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                        "attemptId, userId and detection are required");
            }
            Attempt attempt = attempts.computeIfAbsent(item.attemptId(), id -> attemptRepo
                    .findById(id).orElse(null));
            if (attempt == null) {
                rejected++;
                results.add(ItemResult.of(item.detection().eventId(), Status.REJECTED,
                        AiDetectionBatchResponse.REASON_ATTEMPT_NOT_FOUND));
                continue;
            }
            if (!Objects.equals(attempt.getUserId(), item.userId())) {
                rejected++;
                results.add(ItemResult.of(item.detection().eventId(), Status.REJECTED,
                        AiDetectionBatchResponse.REASON_NOT_ATTEMPT_OWNER));
                continue;
            }
            ValidatedDetection validated = validate(item.detection(), Set.of(WORKER_RUNTIME));
            Outcome outcome = processOne(attempt, validated, AiProvenance.WORKER_VERIFIED);
            accepted += outcome.accepted() ? 1 : 0;
            duplicates += outcome.duplicate() ? 1 : 0;
            quarantined += outcome.quarantined() ? 1 : 0;
            rejected += outcome.rejected() ? 1 : 0;
            results.add(outcome.result());
        }
        return new AiDetectionBatchResponse(accepted, duplicates, quarantined, rejected, results);
    }

    // ==================================================================
    // shared core
    // ==================================================================

    private AiDetectionBatchResponse process(Attempt attempt, List<ValidatedDetection> validated,
            AiProvenance provenance) {
        List<ItemResult> results = new ArrayList<>(validated.size());
        int accepted = 0;
        int duplicates = 0;
        int quarantined = 0;
        int rejected = 0;
        for (ValidatedDetection detection : validated) {
            Outcome outcome = processOne(attempt, detection, provenance);
            accepted += outcome.accepted() ? 1 : 0;
            duplicates += outcome.duplicate() ? 1 : 0;
            quarantined += outcome.quarantined() ? 1 : 0;
            rejected += outcome.rejected() ? 1 : 0;
            results.add(outcome.result());
        }
        return new AiDetectionBatchResponse(accepted, duplicates, quarantined, rejected, results);
    }

    /** CONTRACT §3.2 time binding + media binding, then idempotent insert. */
    private Outcome processOne(Attempt attempt, ValidatedDetection detection,
            AiProvenance provenance) {
        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            // Finished attempt: stored + flagged, NEVER aggregates.
            store(detection, attempt, provenance, null, true, QUARANTINE_ATTEMPT_NOT_IN_PROGRESS);
            return Outcome.quarantined(detection.eventId(), QUARANTINE_ATTEMPT_NOT_IN_PROGRESS);
        }

        MonitoringMediaBinding binding = activeBinding(attempt.getId(), attempt.getUserId());
        if (binding == null) {
            return Outcome.rejected(detection.eventId(),
                    AiDetectionBatchResponse.REASON_NO_ACTIVE_MEDIA);
        }

        // Time binding: [attempt.startedAt − 5s, now + 5s] (server clock).
        Instant windowStart = attempt.getStartedAt().minus(TIME_BIND_SLACK);
        Instant windowEnd = Instant.now(clock).plus(TIME_BIND_SLACK);
        if (detection.observedAt().isBefore(windowStart)) {
            long lateMs = Duration.between(detection.observedAt(), windowStart).toMillis();
            store(detection, attempt, provenance, binding.getId(), true,
                    QUARANTINE_OBSERVED_AT_OUT_OF_WINDOW, lateMs);
            return Outcome.quarantined(detection.eventId(), QUARANTINE_OBSERVED_AT_OUT_OF_WINDOW);
        }
        if (detection.observedAt().isAfter(windowEnd)) {
            long lateMs = Duration.between(windowEnd, detection.observedAt()).toMillis();
            store(detection, attempt, provenance, binding.getId(), true,
                    QUARANTINE_OBSERVED_AT_OUT_OF_WINDOW, lateMs);
            return Outcome.quarantined(detection.eventId(), QUARANTINE_OBSERVED_AT_OUT_OF_WINDOW);
        }

        int inserted = store(detection, attempt, provenance, binding.getId(), false, null, null);
        if (inserted == 0) {
            return Outcome.duplicate(detection.eventId());
        }
        // Aggregation in the SAME transaction (CONTRACT §5).
        aggregationService.aggregate(attempt, detection.toAggregated(), provenance,
                detection.maxConfidence());
        return Outcome.accepted(detection.eventId());
    }

    private int store(ValidatedDetection detection, Attempt attempt, AiProvenance provenance,
            UUID mediaBindingId, boolean quarantined, String quarantineReason) {
        return store(detection, attempt, provenance, mediaBindingId, quarantined,
                quarantineReason, null);
    }

    private int store(ValidatedDetection detection, Attempt attempt, AiProvenance provenance,
            UUID mediaBindingId, boolean quarantined, String quarantineReason, Long lateMs) {
        return detectionRepo.insertIfAbsent(
                attempt.getId(), attempt.getExamSessionId(), attempt.getUserId(),
                mediaBindingId, detection.eventId(), provenance.name(),
                detection.type().name(), detection.modelId(), detection.modelVersion(),
                detection.runtime(), Timestamp.from(detection.observedAt()), quarantined,
                quarantineReason, detection.frameWidth(), detection.frameHeight(),
                detection.processingMs(), detection.payloadJson(), lateMs);
    }

    private MonitoringMediaBinding activeBinding(Long attemptId, Long userId) {
        List<MonitoringMediaBinding> bindings =
                bindingRepo.findActiveByAttemptIdAndUserId(attemptId, userId);
        return bindings.isEmpty() ? null : bindings.get(0);
    }

    private void requireBatchShape(List<?> items) {
        if (items == null || items.isEmpty()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "detections list must not be empty");
        }
        if (items.size() > properties.getMaxBatchSize()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_BATCH_TOO_LARGE);
        }
    }

    /** Atomic per-item validation (mirrors the P4-A ingest conventions). */
    private ValidatedDetection validate(AiDetectionBatchRequest.Item item, Set<String> runtimes) {
        if (item == null || item.eventId() == null) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "eventId is required");
        }
        AiDetectionType type = AiDetectionType.parse(item.eventType());
        if (type == null) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_EVENT_TYPE_REJECTED);
        }
        if (item.observedAt() == null
                || item.observedAt().isBefore(OBSERVED_TS_MIN)
                || item.observedAt().isAfter(OBSERVED_TS_MAX)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "observedAt outside the accepted sanity window");
        }
        if (item.sequence() == null || item.sequence() < 0) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "sequence must be >= 0");
        }
        if (item.modelId() == null || item.modelId().isBlank()
                || item.modelVersion() == null || item.modelVersion().isBlank()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "modelId and modelVersion are required");
        }
        // Length bound enforced here as well: the worker path's nested item
        // must not overflow the VARCHAR columns with a 500 (P3-7 review).
        if (item.modelId().length() > 40 || item.modelVersion().length() > 40) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "modelId/modelVersion exceed 40 characters");
        }
        if (item.runtime() == null || !runtimes.contains(item.runtime())) {
            // A browser path can never claim the worker runtime and vice
            // versa — provenance is structural, not self-reported.
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "runtime is not valid for this ingest path");
        }
        if (item.frameWidth() == null || item.frameHeight() == null
                || item.frameWidth() < 1 || item.frameHeight() < 1) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "frameWidth and frameHeight must be >= 1");
        }
        if (item.processingMs() == null || item.processingMs() < 0) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "processingMs must be >= 0");
        }

        Double maxConfidence = null;
        ArrayNode payload = objectMapper.createArrayNode();
        List<AiDetectionBatchRequest.DetectedObject> boxes =
                item.detections() == null ? List.of() : item.detections();
        if (boxes.size() > properties.getMaxDetectionsPerEvent()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_BATCH_TOO_LARGE,
                    "detections per event exceed the size bound");
        }
        for (AiDetectionBatchRequest.DetectedObject box : boxes) {
            if (box == null || box.classId() == null || box.label() == null
                    || box.label().isBlank() || box.confidence() == null
                    || box.confidence() < 0 || box.confidence() > 1
                    || box.bbox() == null || box.bbox().size() != 4
                    || box.bbox().stream().anyMatch(v -> v == null || v < 0 || v > 1)) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                        "detection box must carry classId, label, confidence 0..1 and a "
                                + "4-value bbox normalized 0..1");
            }
            ObjectNode boxNode = payload.addObject();
            boxNode.put("classId", box.classId());
            boxNode.put("label", box.label());
            boxNode.put("confidence", box.confidence());
            ArrayNode bbox = boxNode.putArray("bbox");
            box.bbox().forEach(bbox::add);
            if (maxConfidence == null || box.confidence() > maxConfidence) {
                maxConfidence = box.confidence();
            }
        }
        String payloadJson = payload.toString();
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_METADATA_TOO_LARGE);
        }
        return new ValidatedDetection(item.eventId(), type, item.observedAt(), item.sequence(),
                item.modelId(), item.modelVersion(), item.runtime(), item.frameWidth(),
                item.frameHeight(), item.processingMs(), payloadJson, maxConfidence);
    }

    // ==================================================================
    // outcome / validated carriers
    // ==================================================================

    private record Outcome(ItemResult result, boolean accepted, boolean duplicate,
            boolean quarantined, boolean rejected) {

        static Outcome accepted(UUID eventId) {
            return new Outcome(ItemResult.of(eventId, Status.ACCEPTED), true, false, false, false);
        }

        static Outcome duplicate(UUID eventId) {
            return new Outcome(ItemResult.of(eventId, Status.DUPLICATE), false, true, false, false);
        }

        static Outcome quarantined(UUID eventId, String reason) {
            return new Outcome(ItemResult.of(eventId, Status.QUARANTINED_LATE, reason),
                    false, false, true, false);
        }

        static Outcome rejected(UUID eventId, String reason) {
            return new Outcome(ItemResult.of(eventId, Status.REJECTED, reason),
                    false, false, false, true);
        }
    }

    record ValidatedDetection(
            UUID eventId,
            AiDetectionType type,
            Instant observedAt,
            long sequence,
            String modelId,
            String modelVersion,
            String runtime,
            int frameWidth,
            int frameHeight,
            int processingMs,
            String payloadJson,
            Double maxConfidence) {

        AiAlertAggregationService.AggregatedDetection toAggregated() {
            return new AiAlertAggregationService.AggregatedDetection(
                    eventId, type, observedAt, modelId, modelVersion, payloadJson);
        }
    }
}
