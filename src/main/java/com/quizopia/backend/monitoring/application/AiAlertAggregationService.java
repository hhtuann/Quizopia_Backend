package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.AiAlertType;
import com.quizopia.backend.monitoring.domain.model.AiDetectionType;
import com.quizopia.backend.monitoring.domain.model.AiProvenance;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.repository.MonitoringAiAlertRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-side temporal aggregation of accepted AI detections into alert
 * windows (P4-C, issue #90, CONTRACT §5).
 *
 * <p><b>Same transaction as the detection insert</b> ({@code MANDATORY}):
 * insert detection → aggregate → record activity event; the alert, its
 * window math and its {@code AI_ALERT_RAISED}/{@code AI_ALERT_UPDATED}
 * activity events commit or roll back with the detection row. The
 * AFTER_COMMIT {@code AttemptActivityBroadcaster} then pings the EXISTING
 * teacher topic — no new STOMP destination, no new envelope type; the
 * durable REST feed is the source of truth on reconnect.
 *
 * <p><b>Window semantics (CONTRACT §5):</b> for each ACCEPTED detection —
 * <ul>
 *   <li>no OPEN window → open one (count=1) → {@code AI_ALERT_RAISED};</li>
 *   <li>OPEN window and {@code observedAt <= lastDetectedAt + cooldown}
 *       → EXTEND (count+1 atomic, max confidence, latest payload) →
 *       {@code AI_ALERT_UPDATED} ONLY at counts {2,4,8,16,32,64}
 *       (notification-spam cap);</li>
 *   <li>gap &gt; cooldown → CLOSE the old window (it keeps its own
 *       first/last/count evidence as a finished incident) and open a NEW
 *       one → {@code AI_ALERT_RAISED}.</li>
 * </ul>
 *
 * <p>Duplicates (idempotent insert 0 rows) and QUARANTINED detections
 * NEVER reach this service. Alerts are observations for the teacher
 * feed — never verdicts, never score input (CONTRACT §8; no write path
 * to grades/grade_items exists anywhere in this class).
 */
@Service
public class AiAlertAggregationService {

    /** AI_ALERT_UPDATED is emitted only at these detection counts. */
    private static final Set<Integer> UPDATE_NOTIFY_COUNTS = Set.of(2, 4, 8, 16, 32, 64);

    private final MonitoringAiAlertRepository alertRepo;
    private final ActivityEventRecorder activityRecorder;
    private final AiIngestProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiAlertAggregationService(MonitoringAiAlertRepository alertRepo,
            ActivityEventRecorder activityRecorder,
            AiIngestProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.alertRepo = alertRepo;
        this.activityRecorder = activityRecorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Aggregates one ACCEPTED detection. Joins the caller's transaction
     * ({@code MANDATORY} — an alert outside the detection's transaction
     * would violate the atomicity contract of CONTRACT §5).
     *
     * @return the id of an alert window OPENED by this detection (null when
     *         it only extended an existing window) — P4-D uses it to ask the
     *         student's browser for an evidence snapshot at that moment.
     * @param maxConfidence max confidence of the detection's boxes
     *                      (null when the payload carries no boxes)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long aggregate(Attempt attempt, AggregatedDetection detection,
            AiProvenance provenance, Double maxConfidence) {
        AiAlertType alertType = AiAlertType.fromDetection(detection.type());

        MonitoringAiAlert open = alertRepo
                .findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
                        attempt.getId(), alertType, MonitoringAiAlert.STATUS_OPEN)
                .orElse(null);

        // Gap beyond the cooldown: the incident window ends (CLOSED keeps
        // its own evidence) and this detection opens a NEW window.
        // P2-1: a window also ENDS when its total span exceeds the
        // configured maximum — repeated in-cooldown detections cannot
        // stretch one "incident" indefinitely (bounded ledger).
        if (open != null && (detection.observedAt()
                .isAfter(open.getLastDetectedAt().plus(cooldownOf(alertType)))
                || detection.observedAt()
                        .isAfter(open.getFirstDetectedAt()
                                .plus(properties.getAlertMaxWindowSpan())))) {
            alertRepo.closeOpenAlert(open.getId());
            open = null;
        }

        if (open == null) {
            int inserted = alertRepo.insertOpenIfAbsent(
                    attempt.getId(), attempt.getExamSessionId(), attempt.getUserId(),
                    alertType.name(), Timestamp.from(detection.observedAt()), maxConfidence,
                    detection.payloadJson(), detection.modelId(), detection.modelVersion(),
                    provenance.name());
            if (inserted == 1) {
                MonitoringAiAlert raised = alertRepo
                        .findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
                                attempt.getId(), alertType, MonitoringAiAlert.STATUS_OPEN)
                        .orElseThrow(() -> new IllegalStateException(
                                "AI alert window missing right after insert"));
                recordAlertEvent(raised.getId(), attempt, ActivityEventType.AI_ALERT_RAISED,
                        detection, provenance, maxConfidence, 1);
                return raised.getId();
            }
            // Lost an open-race to a concurrent batch: extend the winner's
            // window instead (the partial unique index guarantees exactly
            // one OPEN window per attempt+type). P2-6 (review): the gap
            // check above ran against the OLD window — re-check against the
            // winner so two separated incidents never merge into one.
            open = alertRepo
                    .findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
                            attempt.getId(), alertType, MonitoringAiAlert.STATUS_OPEN)
                    .orElseThrow(() -> new IllegalStateException(
                            "AI alert OPEN window missing after open-race"));
            if (detection.observedAt()
                    .isAfter(open.getLastDetectedAt().plus(cooldownOf(alertType)))) {
                alertRepo.closeOpenAlert(open.getId());
                inserted = alertRepo.insertOpenIfAbsent(
                        attempt.getId(), attempt.getExamSessionId(), attempt.getUserId(),
                        alertType.name(), Timestamp.from(detection.observedAt()), maxConfidence,
                        detection.payloadJson(), detection.modelId(), detection.modelVersion(),
                        provenance.name());
                if (inserted == 1) {
                    MonitoringAiAlert reopened = alertRepo
                            .findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
                                    attempt.getId(), alertType, MonitoringAiAlert.STATUS_OPEN)
                            .orElseThrow(() -> new IllegalStateException(
                                    "AI alert window missing right after re-open"));
                    recordAlertEvent(reopened.getId(), attempt, ActivityEventType.AI_ALERT_RAISED,
                            detection, provenance, maxConfidence, 1);
                    return reopened.getId();
                }
                open = alertRepo
                        .findFirstByAttemptIdAndAlertTypeAndStatusOrderByIdDesc(
                                attempt.getId(), alertType, MonitoringAiAlert.STATUS_OPEN)
                        .orElseThrow(() -> new IllegalStateException(
                                "AI alert OPEN window missing after re-open race"));
            }
        }

        alertRepo.extendOpenAlert(open.getId(), Timestamp.from(detection.observedAt()),
                maxConfidence, detection.payloadJson(), detection.modelId(),
                detection.modelVersion());
        Integer count = alertRepo.detectionCountOf(open.getId());
        if (count != null && UPDATE_NOTIFY_COUNTS.contains(count)) {
            recordAlertEvent(open.getId(), attempt, ActivityEventType.AI_ALERT_UPDATED,
                    detection, provenance, maxConfidence, count);
        }
        return null;
    }

    private Duration cooldownOf(AiAlertType alertType) {
        return alertType == AiAlertType.PERSON_NOT_VISIBLE
                ? properties.getAlertCooldownPersonNotVisible()
                : properties.getAlertCooldown();
    }

    private void recordAlertEvent(Long alertId, Attempt attempt, ActivityEventType type,
            AggregatedDetection detection, AiProvenance provenance,
            Double maxConfidence, int detectionCount) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("alertId", alertId);
        metadata.put("alertType", AiAlertType.fromDetection(detection.type()).name());
        metadata.put("detectionCount", detectionCount);
        metadata.put("modelId", detection.modelId());
        metadata.put("modelVersion", detection.modelVersion());
        metadata.put("provenance", provenance.name());
        metadata.put("detectionEventId", detection.eventId().toString());
        if (maxConfidence != null) {
            metadata.put("maxConfidence", maxConfidence);
        }
        activityRecorder.recordServerEvent(attempt.getId(), attempt.getExamSessionId(),
                attempt.getUserId(), type, Instant.now(clock), metadata.toString());
    }

    /** A fully validated ACCEPTED-bound detection (see the ingest service). */
    public record AggregatedDetection(
            UUID eventId,
            AiDetectionType type,
            Instant observedAt,
            String modelId,
            String modelVersion,
            String payloadJson) {
    }
}
