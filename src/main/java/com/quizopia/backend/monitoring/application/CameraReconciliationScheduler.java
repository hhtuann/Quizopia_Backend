package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.roomservice.LkParticipant;
import com.quizopia.backend.media.roomservice.LkTrack;
import com.quizopia.backend.media.roomservice.MediaRoomGateway;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles LiveKit room truth against camera-connection bookkeeping
 * (P4-B4, issue #87). Webhooks are the fast path; this sweep is the backstop
 * for lost/undelivered events (e.g. backend restart during a disconnect).
 *
 * <p>ADR-08 shape: {@code listParticipants} runs OUTSIDE any DB transaction
 * (the gateway enforces this); each session is then reconciled in its own
 * short transaction, so one session's failure cannot roll back the rest.
 *
 * <p>Event semantics mirror the webhook path exactly: a CAMERA_* activity
 * event is emitted only when a presence period is actually opened/closed by
 * this sweep AND the bound attempt is IN_PROGRESS — matched participants
 * only get {@code last_verified_at} touched, so repeated sweeps are
 * event-idempotent.
 */
@Component
@ConditionalOnProperty(
        name = "quizopia.monitoring.camera.reconcile-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CameraReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CameraReconciliationScheduler.class);

    private static final String RECONCILED = "reconciled";
    private static final String ATTEMPT_IN_PROGRESS = "IN_PROGRESS";

    private final MediaRoomGateway gateway;
    private final LiveKitProperties liveKit;
    private final MonitoringCameraQueries queries;
    private final MonitoringCameraConnectionRepository connections;
    private final ActivityEventRecorder activity;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CameraReconciliationScheduler(MediaRoomGateway gateway,
            LiveKitProperties liveKit,
            MonitoringCameraQueries queries,
            MonitoringCameraConnectionRepository connections,
            ActivityEventRecorder activity,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.liveKit = liveKit;
        this.queries = queries;
        this.connections = connections;
        this.activity = activity;
        this.json = json;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Periodic drift sweep (default 30s, configurable; disabled ⇒ bean absent). */
    @Scheduled(fixedDelayString = "${quizopia.monitoring.camera.reconcile-interval:30s}")
    public void reconcilePeriodically() {
        if (!liveKit.isEnabled()) {
            return;
        }
        sweepSafely("periodic");
    }

    /** One sweep shortly after startup — room state comes from LiveKit, never from webhooks. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        if (!liveKit.isEnabled()) {
            return;
        }
        Thread.ofVirtual().name("camera-reconcile-startup").start(() -> sweepSafely("startup"));
    }

    private void sweepSafely(String trigger) {
        try {
            sweep();
        } catch (Exception e) {
            log.warn("camera reconciliation sweep failed (trigger={})", trigger, e);
        }
    }

    void sweep() {
        List<MonitoringCameraQueries.SessionRoom> candidates = queries.findMonitoredOpenSessions();
        for (MonitoringCameraQueries.SessionRoom candidate : candidates) {
            List<LkParticipant> participants;
            try {
                // Media I/O strictly outside the per-session DB transaction.
                participants = gateway.listParticipants(candidate.roomName());
            } catch (TwirpMediaRoomGateway.LiveKitUnavailableException e) {
                log.warn("skipping camera reconciliation for session {} (room {} unreachable)",
                        candidate.sessionId(), candidate.roomName(), e);
                continue;
            }
            try {
                reconcileSession(candidate.sessionId(), participants);
            } catch (Exception e) {
                log.warn("camera reconciliation failed for session {}", candidate.sessionId(), e);
            }
        }
    }

    private void reconcileSession(long sessionId, List<LkParticipant> participants) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            List<MonitoringCameraQueries.BindingRow> bindings =
                    queries.findActiveBindingsBySession(sessionId);
            if (bindings.isEmpty()) {
                return;
            }
            Map<UUID, MonitoringCameraQueries.OpenConnectionRow> openRows =
                    queries.findOpenConnectionsBySession(sessionId);
            for (MonitoringCameraQueries.BindingRow binding : bindings) {
                Optional<LkParticipant> participant = participants.stream()
                        .filter(p -> binding.mediaIdentity().equals(p.identity()))
                        .findFirst();
                boolean cameraLive = participant.map(LkParticipant::hasActiveCamera).orElse(false);
                MonitoringCameraQueries.OpenConnectionRow open = openRows.get(binding.id());
                if (cameraLive && open == null) {
                    openMissingPeriod(binding, cameraSid(participant.orElseThrow()), now);
                } else if (!cameraLive && open != null) {
                    closeGhostPeriod(binding, now);
                } else if (cameraLive) {
                    connections.touchOpenRow(binding.id(), Timestamp.from(now));
                }
                // No camera and no open row: idle — nothing to do (no event spam).
            }
        });
    }

    private void openMissingPeriod(MonitoringCameraQueries.BindingRow binding, String trackSid, Instant now) {
        boolean opened = connections.insertOpenIfAbsent(
                UUID.randomUUID(), binding.id(), trackSid, Timestamp.from(now)) > 0;
        if (opened && attemptInProgress(binding.attemptId())) {
            record(binding, ActivityEventType.CAMERA_CONNECTED, now, reconciledMetadata());
        }
    }

    private void closeGhostPeriod(MonitoringCameraQueries.BindingRow binding, Instant now) {
        boolean closed = connections.closeOpenRow(binding.id(), RECONCILED, Timestamp.from(now)) > 0;
        if (closed && attemptInProgress(binding.attemptId())) {
            record(binding, ActivityEventType.CAMERA_DISCONNECTED, now, reconciledMetadata());
        }
    }

    private static String cameraSid(LkParticipant participant) {
        return participant.tracks() == null ? null : participant.tracks().stream()
                .filter(t -> "VIDEO".equals(t.type()) && "CAMERA".equals(t.source()) && !t.muted())
                .map(LkTrack::sid)
                .findFirst()
                .orElse(null);
    }

    private boolean attemptInProgress(Long attemptId) {
        return ATTEMPT_IN_PROGRESS.equals(queries.attemptStatus(attemptId));
    }

    private void record(MonitoringCameraQueries.BindingRow binding, ActivityEventType type,
            Instant occurredAt, String metadataJson) {
        activity.recordServerEvent(binding.attemptId(), binding.sessionId(), binding.userId(),
                type, occurredAt, metadataJson);
    }

    private String reconciledMetadata() {
        ObjectNode node = json.createObjectNode();
        node.put("reason", RECONCILED);
        return node.toString();
    }
}
