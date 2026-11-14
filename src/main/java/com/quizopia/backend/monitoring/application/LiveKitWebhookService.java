package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.media.webhook.LiveKitWebhookEvent;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.MonitoringCameraConnection;
import com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository;
import com.quizopia.backend.monitoring.repository.MonitoringWebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Routes verified LiveKit webhook events onto camera-connection state and the
 * activity timeline (P4-B4, issue #87).
 *
 * <p>Idempotency contract: the dedupe ledger insert and the domain routing
 * run in ONE transaction, ledger first — a duplicate delivery (same sha256
 * dedupe key) is a no-op before any state is touched, and a crash between
 * ledger insert and routing rolls both back, so LiveKit's retry can never
 * double-apply. Camera-connection rows are additionally guarded by the
 * one-open-row-per-binding partial unique index.
 *
 * <p>Identity semantics (ADR-08): bindings resolve by {@code media_identity}
 * — a fresh opaque UUID per preflight — so a late webhook can never be
 * reattributed to a later attempt of the same user. Activity events are
 * emitted only while the bound attempt is IN_PROGRESS; connection rows are
 * bookkeeping and are always maintained.
 */
@Service
public class LiveKitWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookService.class);

    /** A prior closed period within this window upgrades CONNECTED to RECONNECTED. */
    static final Duration RECONNECT_WINDOW = Duration.ofMinutes(30);

    private static final String ATTEMPT_IN_PROGRESS = "IN_PROGRESS";

    private final MonitoringWebhookEventRepository ledger;
    private final MonitoringCameraConnectionRepository connections;
    private final MonitoringCameraQueries queries;
    private final ActivityEventRecorder activity;
    private final ObjectMapper json;
    private final Clock clock;

    public LiveKitWebhookService(MonitoringWebhookEventRepository ledger,
            MonitoringCameraConnectionRepository connections,
            MonitoringCameraQueries queries,
            ActivityEventRecorder activity,
            ObjectMapper json,
            Clock clock) {
        this.ledger = ledger;
        this.connections = connections;
        this.queries = queries;
        this.activity = activity;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Accepts one verified webhook delivery. Always "succeeds" from the
     * caller's viewpoint: unknown events are ledger-only and routing
     * no-ops are fine (the controller answers 200 either way).
     */
    @Transactional
    public void handle(LiveKitWebhookEvent event) {
        Instant now = clock.instant();
        int accepted = ledger.insertIfAbsent(event.dedupeKey(), event.event(), Timestamp.from(now));
        if (accepted == 0) {
            log.debug("duplicate webhook delivery skipped (event={}, dedupeKey={})",
                    event.event(), event.dedupeKey());
            return;
        }
        if (event.isCameraTrackPublished()) {
            routeCameraPublished(event, now);
        } else if (event.isCameraTermination()) {
            routeCameraTermination(event, now);
        }
        // Everything else (room lifecycle, participant_joined, egress/ingress,
        // teacher/admin identities) is ledger-only in P4-B.
    }

    // ------------------------------------------------------------------
    // track_published (VIDEO/CAMERA)
    // ------------------------------------------------------------------

    private void routeCameraPublished(LiveKitWebhookEvent event, Instant now) {
        Optional<MonitoringCameraQueries.BindingRow> binding =
                queries.findActiveBindingByMediaIdentity(event.participantIdentity());
        if (binding.isEmpty()) {
            // Unknown/teacher/admin identity or binding already terminated —
            // ledger-only.
            return;
        }
        MonitoringCameraQueries.BindingRow b = binding.get();
        boolean openedNewPeriod = ensureOpenConnection(b.id(), event.trackSid(), now);
        if (!attemptInProgress(b.attemptId())) {
            // Pre-attempt publish or finished attempt: keep the connection
            // row (presence bookkeeping) but never emit timeline events.
            return;
        }
        if (!openedNewPeriod) {
            // Duplicate publish for an already-open period (retransmission
            // with a fresh event id) — row refreshed, no second event.
            return;
        }
        Optional<MonitoringCameraConnection> priorClosed =
                connections.findFirstByBindingIdAndDisconnectedAtIsNotNullOrderByDisconnectedAtDesc(b.id());
        if (priorClosed.isPresent()
                && !priorClosed.get().getDisconnectedAt().isBefore(now.minus(RECONNECT_WINDOW))) {
            long gapSeconds = Duration.between(priorClosed.get().getDisconnectedAt(), now).toSeconds();
            record(b, ActivityEventType.CAMERA_RECONNECTED, now,
                    metadata("webhook", event.trackSid(), gapSeconds));
        } else {
            record(b, ActivityEventType.CAMERA_CONNECTED, now,
                    metadata("webhook", event.trackSid(), null));
        }
    }

    /**
     * Opens or refreshes the binding's open connection row. Returns true only
     * when this call started a NEW presence period (the single condition for
     * emitting a connected-family activity event).
     */
    private boolean ensureOpenConnection(UUID bindingId, String trackSid, Instant now) {
        int claimed = connections.claimOpenRow(bindingId, trackSid, Timestamp.from(now));
        if (claimed > 0) {
            return false; // period already open — refreshed track/verification
        }
        int inserted = connections.insertOpenIfAbsent(
                UUID.randomUUID(), bindingId, trackSid, Timestamp.from(now));
        if (inserted > 0) {
            return true;
        }
        // Lost a race (concurrent webhook/reconcile opened the row between the
        // two statements) — refresh the winner's row; no event from this call.
        connections.claimOpenRow(bindingId, trackSid, Timestamp.from(now));
        return false;
    }

    // ------------------------------------------------------------------
    // track_unpublished / participant_left / aborted / disconnected
    // ------------------------------------------------------------------

    private void routeCameraTermination(LiveKitWebhookEvent event, Instant now) {
        if ("track_unpublished".equals(event.event()) && !isCameraTrack(event)) {
            return; // mic/screen track gone — ledger only
        }
        Optional<MonitoringCameraQueries.BindingRow> binding =
                queries.findLatestBindingByMediaIdentity(event.participantIdentity());
        if (binding.isEmpty()) {
            return; // identity without a binding — ledger only
        }
        MonitoringCameraQueries.BindingRow b = binding.get();
        Optional<MonitoringCameraConnection> open =
                connections.findByBindingIdAndDisconnectedAtIsNull(b.id());
        if (open.isEmpty()) {
            return; // already closed (late webhook) — nothing to close, no event
        }
        if (event.trackSid() != null && open.get().getTrackSid() != null
                && !event.trackSid().equals(open.get().getTrackSid())) {
            return; // termination of a different track (non-camera) — ledger only
        }
        int closed = connections.closeOpenRow(b.id(), event.event(), Timestamp.from(now));
        if (closed == 1 && attemptInProgress(b.attemptId())) {
            record(b, ActivityEventType.CAMERA_DISCONNECTED, now,
                    metadata(event.event(), event.trackSid(), null));
        }
    }

    private static boolean isCameraTrack(LiveKitWebhookEvent event) {
        return "VIDEO".equals(event.trackType()) && "CAMERA".equals(event.trackSource());
    }

    private boolean attemptInProgress(Long attemptId) {
        return ATTEMPT_IN_PROGRESS.equals(queries.attemptStatus(attemptId));
    }

    private void record(MonitoringCameraQueries.BindingRow binding, ActivityEventType type,
            Instant occurredAt, String metadataJson) {
        activity.recordServerEvent(binding.attemptId(), binding.sessionId(), binding.userId(),
                type, occurredAt, metadataJson);
        log.debug("camera activity event {} recorded (binding={}, attempt={})",
                type, binding.id(), binding.attemptId());
    }

    private String metadata(String reason, String trackSid, Long gapSeconds) {
        ObjectNode node = json.createObjectNode();
        node.put("reason", reason);
        if (trackSid != null) {
            node.put("trackSid", trackSid);
        }
        if (gapSeconds != null) {
            node.put("gapSeconds", gapSeconds);
        }
        return node.toString();
    }
}
