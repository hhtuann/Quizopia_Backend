package com.quizopia.backend.evidence.application;

import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.evidence.application.RecordingEvents.CameraPresence;
import com.quizopia.backend.evidence.application.RecordingEvents.EgressUpdate;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import com.quizopia.backend.evidence.repository.MonitoringRecordingJobRepository;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.egress.EgressGateway;
import com.quizopia.backend.media.egress.EgressGateway.S3Output;
import com.quizopia.backend.media.storage.EvidenceStorage;
import com.quizopia.backend.media.storage.EvidenceStorageProperties;
import com.quizopia.backend.monitoring.application.ActivityEventRecorder;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository;
import com.quizopia.backend.monitoring.repository.MonitoringMediaBindingRepository;
import com.quizopia.backend.monitoring.repository.MonitoringMediaRoomRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * D2/D3 coordinator (issues #92/#93, ADR-009 §2.2/§2.4): decides recording
 * work from the FROZEN session policy, drives the LiveKit egress lifecycle
 * from camera presence + egress webhooks, and keeps the rolling buffer
 * bounded.
 *
 * <p>All egress/storage calls run strictly outside DB transactions
 * (AFTER_COMMIT listeners + gateway guards). Every failure path is
 * isolated: recording problems mark {@code monitoring_recording_jobs}
 * FAILED and emit {@code RECORDING_FAILED} activity — they can NEVER fail
 * an attempt, autosave, submit or grade flow (issue §6).
 *
 * <p>Rolling buffer honesty: pulses are STARTED when the camera publishes
 * (before any alert exists) and stay bounded by keep-N deletion; a clip can
 * therefore only reference footage that was actually on disk first.
 */
@Component
public class RecordingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RecordingCoordinator.class);

    private final MonitoringRecordingJobRepository jobs;
    private final AttemptRepository attempts;
    private final MonitoringMediaBindingRepository bindings;
    private final MonitoringCameraConnectionRepository connections;
    private final MonitoringMediaRoomRepository rooms;
    private final EgressGateway egress;
    private final EvidenceStorage storage;
    private final EvidenceStorageProperties storageProperties;
    private final EvidenceProperties properties;
    private final EvidencePolicyReader policyReader;
    private final ActivityEventRecorder activity;
    /**
     * Single daemon thread for ALL coordinator work. Two jobs: (1) pulse
     * duty-cycle — the pinned egress protocol has no {@code max_duration}
     * on file outputs, so the coordinator stops each pulse itself after
     * {@code pulse-seconds} (+ grace); (2) serialized AFTER_COMMIT handling
     * — during AFTER_COMMIT listeners the thread still carries the committed
     * transaction's context, and the media/storage gateways' no-transaction
     * guard would spuriously fire (observed: "egress stop failed … inside a
     * DB transaction"). Running here gives every gateway call a clean
     * thread, deterministic FIFO ordering, and daemon shutdown semantics.
     */
    private final java.util.concurrent.ScheduledExecutorService coordinatorExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "evidence-coordinator");
                thread.setDaemon(true);
                return thread;
            });
    /** Start-serialization per attempt (in-JVM; single-instance deploy). */
    private final java.util.Map<Long, Object> startLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * REQUIRES_NEW: the coordinator runs inside AFTER_COMMIT synchronizations
     * where REQUIRED propagation would silently join the ALREADY-COMMITTED
     * transaction (modifying statements then fail with "No active
     * transaction"). Every write here opens its own short transaction.
     */
    private final TransactionTemplate txNew;
    private final ObjectMapper json;
    private final Clock clock;
    private final LiveKitProperties liveKit;

    public RecordingCoordinator(MonitoringRecordingJobRepository jobs,
            AttemptRepository attempts,
            MonitoringMediaBindingRepository bindings,
            MonitoringCameraConnectionRepository connections,
            MonitoringMediaRoomRepository rooms,
            EgressGateway egress,
            EvidenceStorage storage,
            EvidenceStorageProperties storageProperties,
            EvidenceProperties properties,
            EvidencePolicyReader policyReader,
            ActivityEventRecorder activity,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Clock clock,
            LiveKitProperties liveKit) {
        this.jobs = jobs;
        this.attempts = attempts;
        this.bindings = bindings;
        this.connections = connections;
        this.rooms = rooms;
        this.egress = egress;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.properties = properties;
        this.policyReader = policyReader;
        this.activity = activity;
        org.springframework.transaction.support.DefaultTransactionDefinition def =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txNew = new TransactionTemplate(transactionManager, def);
        this.json = json;
        this.clock = clock;
        this.liveKit = liveKit;
    }

    // ==================================================================
    // Camera presence (webhook / attempt start / submit) — AFTER COMMIT
    // ==================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCameraPresence(CameraPresence event) {
        coordinatorExecutor.execute(() -> {
            try {
                if (!event.connected()) {
                    stopActiveForBinding(event.bindingId(), "camera_terminated");
                    return;
                }
                evaluateAndStart(event.bindingId(), event.attemptId(), event.examSessionId());
            } catch (RuntimeException e) {
                // Never propagate into the webhook/attempt flow.
                log.warn("recording evaluation failed (binding={}, attempt={}): {}",
                        event.bindingId(), event.attemptId(), e.getMessage());
            }
        });
    }

    /** Starts the policy-decided recording work unless one is already active. */
    private void evaluateAndStart(UUID bindingId, long attemptId, long examSessionId) {
        synchronized (startLocks.computeIfAbsent(attemptId, key -> new Object())) {
            evaluateAndStartLocked(bindingId, attemptId, examSessionId);
        }
    }

    private void evaluateAndStartLocked(UUID bindingId, long attemptId, long examSessionId) {
        if (!liveKit.isEnabled()) {
            return;
        }
        EvidencePolicyReader.MediaPolicy policy = policyReader.policyOf(examSessionId);
        if (!policy.monitoringEnabled()
                || (!policy.fullRecordingEnabled() && !policy.rollingBufferActive())) {
            return; // recording OFF and clips OFF — nothing to run
        }
        String kind = policy.fullRecordingEnabled()
                ? MonitoringRecordingJob.KIND_FULL
                : MonitoringRecordingJob.KIND_ROLLING;
        List<MonitoringRecordingJob> active = jobs.findActiveByAttemptId(attemptId);
        boolean alreadyRunning = active.stream().anyMatch(j -> j.getKind().equals(kind));
        if (alreadyRunning) {
            return;
        }
        // Foreign-kind runs (policy cannot change mid-session, but a stale
        // job from a previous code path) are stopped before starting fresh.
        active.stream().filter(j -> !j.getKind().equals(kind))
                .forEach(j -> stopQuietly(j.getEgressId()));
        Optional<String> room = rooms.findById(examSessionId).map(r -> r.getRoomName());
        Optional<String> trackSid = connections
                .findByBindingIdAndDisconnectedAtIsNull(bindingId)
                .map(c -> c.getTrackSid());
        if (room.isEmpty() || trackSid.isEmpty() || trackSid.get() == null) {
            log.debug("recording skipped: no room/track yet (binding={})", bindingId);
            return;
        }
        startRun(bindingId, attemptId, examSessionId, room.get(), trackSid.get(), kind);
    }

    /** Starts one egress run (FULL segment or ROLLING pulse) and its job row. */
    private void startRun(UUID bindingId, long attemptId, long examSessionId,
            String room, String trackSid, String kind) {
        int pulseIndex = MonitoringRecordingJob.KIND_ROLLING.equals(kind)
                ? nextPulseIndex(attemptId)
                : 0;
        String prefix = MonitoringRecordingJob.KIND_FULL.equals(kind)
                ? "recordings/" + attemptId + "/" + kind.toLowerCase() + "-" + pulseIndex
                : "rolling/" + attemptId + "/" + kind.toLowerCase() + "-" + pulseIndex;
        try {
            EgressGateway.EgressRun run = egress.startVideoTrackRecording(
                    room, trackSid, prefix, s3Output());
            EvidencePolicyReader.MediaPolicy policyForExpiry =
                    policyReader.policyOf(examSessionId);
            MonitoringRecordingJob job = MonitoringRecordingJob.newJob(
                    UUID.randomUUID(), examSessionId, attemptId, bindingId, kind,
                    run.egressId(), room, trackSid, pulseIndex,
                    policyForExpiry.mediaRetentionExpiry(clock.instant()), clock.instant());
            final String initialStatus = mapStatus(run.status());
            final String egressId = run.egressId();
            final Timestamp nowTs = toTimestamp(clock.instant());
            txNew.executeWithoutResult(status -> {
                jobs.save(job);
                jobs.updateFromEgress(egressId, initialStatus, null, null,
                        null, null, null, null, nowTs);
            });
            if (MonitoringRecordingJob.KIND_ROLLING.equals(kind)) {
                long graceMs = properties.getPulseSeconds().toMillis() + 10_000;
                coordinatorExecutor.schedule(() -> pulseTimeout(egressId), graceMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            recordActivity(attemptId, examSessionId, ActivityEventType.RECORDING_STARTED,
                    meta(kind, pulseIndex, "egressId", run.egressId()));
            log.info("recording started (kind={}, attempt={}, egress={})",
                    kind, attemptId, run.egressId());
        } catch (RuntimeException e) {
            log.warn("recording start failed (kind={}, attempt={}): {}",
                    kind, attemptId, e.getMessage());
            recordActivity(attemptId, examSessionId, ActivityEventType.RECORDING_FAILED,
                    meta(kind, pulseIndex, "reason", "EGRESS_START_FAILED"));
        }
    }

    // ==================================================================
    // Egress lifecycle webhooks — AFTER COMMIT
    // ==================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEgressUpdate(EgressUpdate event) {
        coordinatorExecutor.execute(() -> {
            try {
                applyEgressUpdate(event);
            } catch (RuntimeException e) {
                log.warn("egress update failed (egress={}, status={}): {}",
                        event.egressId(), event.status(), e.getMessage());
            }
        });
    }

    private void applyEgressUpdate(EgressUpdate event) {
        Optional<MonitoringRecordingJob> existing = jobs.findByEgressId(event.egressId());
        if (existing.isEmpty()) {
            return; // egress run we do not own (other deployment/tests)
        }
        MonitoringRecordingJob job = existing.get();
        String mapped = mapStatus(event.status());
        String objectKey = null;
        Long bytesValue = null;
        Long durationValue = null;
        if (isTerminal(mapped) && !event.files().isEmpty()) {
            EgressUpdate.File file = event.files().get(event.files().size() - 1);
            objectKey = deriveObjectKey(file.filename());
            bytesValue = file.size() > 0 ? file.size() : null;
            durationValue = file.durationNs() > 0 ? file.durationNs() / 1_000_000 : null;
            // ADR-009 §1: an object key counts only when HEAD-able.
            if (objectKey != null && storage.head(objectKey).isEmpty()) {
                log.warn("egress file not found in storage (egress={}, key={})",
                        event.egressId(), objectKey);
                objectKey = null;
            }
        }
        Instant startedAt = event.startedAtNs() > 0
                ? Instant.ofEpochSecond(event.startedAtNs() / 1_000_000_000L) : null;
        Instant endedAt = event.endedAtNs() > 0
                ? Instant.ofEpochSecond(event.endedAtNs() / 1_000_000_000L) : null;
        final String finalObjectKey = objectKey;
        final String finalStatus = mapped;
        final Long bytes = bytesValue;
        final Long durationMs = durationValue;
        txNew.executeWithoutResult(status -> jobs.updateFromEgress(event.egressId(), finalStatus,
                toTimestamp(startedAt), toTimestamp(endedAt), finalObjectKey, bytes, durationMs,
                event.error(), toTimestamp(clock.instant())));

        if (MonitoringRecordingJob.STATUS_COMPLETE.equals(mapped)) {
            recordActivity(job.getAttemptId(), job.getExamSessionId(),
                    ActivityEventType.RECORDING_STOPPED,
                    meta(job.getKind(), job.getPulseIndex(), "bytes",
                            bytes == null ? "unknown" : String.valueOf(bytes)));
            if (MonitoringRecordingJob.KIND_ROLLING.equals(job.getKind())) {
                cleanupRolling(job.getAttemptId());
                chainNextPulse(job);
            }
        } else if (MonitoringRecordingJob.STATUS_FAILED.equals(mapped)
                || MonitoringRecordingJob.STATUS_ABORTED.equals(mapped)) {
            recordActivity(job.getAttemptId(), job.getExamSessionId(),
                    ActivityEventType.RECORDING_FAILED,
                    meta(job.getKind(), job.getPulseIndex(), "reason",
                            "EGRESS_" + event.status()));
        }
    }

    /** Starts the next pulse while the camera stays connected. */
    private void chainNextPulse(MonitoringRecordingJob finished) {
        boolean cameraOpen = connections
                .findByBindingIdAndDisconnectedAtIsNull(finished.getBindingId())
                .isPresent();
        boolean attemptLive = attempts.findById(finished.getAttemptId())
                .map(a -> "IN_PROGRESS".equals(a.getStatus().name()))
                .orElse(false);
        if (cameraOpen && attemptLive) {
            Optional<String> room = rooms.findById(finished.getExamSessionId())
                    .map(r -> r.getRoomName());
            String trackSid = finished.getTrackSid();
            if (room.isPresent()) {
                startRun(finished.getBindingId(), finished.getAttemptId(),
                        finished.getExamSessionId(), room.get(), trackSid,
                        MonitoringRecordingJob.KIND_ROLLING);
            }
        }
    }

    /**
     * Pulse watchdog: stops a still-running ROLLING pulse (the pinned egress
     * protocol has no {@code max_duration} on file outputs). Terminal states
     * no-op; stop failures only log — the webhook path stays authoritative.
     */
    public void pulseTimeout(String egressId) {
        try {
            jobs.findByEgressId(egressId).ifPresent(job -> {
                if (!MonitoringRecordingJob.KIND_ROLLING.equals(job.getKind())) {
                    return;
                }
                if (MonitoringRecordingJob.STATUS_STARTING.equals(job.getStatus())
                        || MonitoringRecordingJob.STATUS_ACTIVE.equals(job.getStatus())) {
                    stopQuietly(egressId);
                    txNew.executeWithoutResult(status -> jobs.markEnding(egressId,
                            "pulse_timeout", toTimestamp(clock.instant())));
                }
            });
        } catch (RuntimeException e) {
            log.warn("pulse timeout failed (egress={}): {}", egressId, e.getMessage());
        }
    }

    /**
     * Keep-N: deletes older pulse objects; rows stay as gap evidence.
     * P1-2: a row is flagged {@code object_deleted} ONLY after its object is
     * really gone (delete succeeded or HEAD says absent) — partial storage
     * failures leave survivors truthfully un-flagged for the next cycle.
     */
    private void cleanupRolling(long attemptId) {
        List<MonitoringRecordingJob> pulses = jobs.findRollingNewestFirst(attemptId);
        if (pulses.size() <= properties.getRollingKeep()) {
            return;
        }
        List<MonitoringRecordingJob> stale = pulses.subList(properties.getRollingKeep(),
                pulses.size());
        for (MonitoringRecordingJob pulse : stale) {
            if (pulse.getObjectKey() == null || pulse.isObjectDeleted()) {
                continue;
            }
            boolean gone;
            try {
                storage.delete(pulse.getObjectKey());
                gone = true;
            } catch (RuntimeException deleteFailed) {
                try {
                    gone = storage.head(pulse.getObjectKey()).isEmpty();
                } catch (RuntimeException headFailed) {
                    log.warn("rolling cleanup delete+verify failed (key={}): delete={}, head={}",
                            pulse.getObjectKey(), deleteFailed.getMessage(),
                            headFailed.getMessage());
                    gone = false;
                }
            }
            if (gone) {
                txNew.executeWithoutResult(status -> jobs.markObjectDeleted(
                        pulse.getId(), toTimestamp(clock.instant())));
            }
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private void stopActiveForBinding(UUID bindingId, String reason) {
        for (MonitoringRecordingJob job : jobs.findActiveByBindingId(bindingId)) {
            stopQuietly(job.getEgressId());
            // Conditional ENDING: never regress a terminal state written by a
            // concurrently processed egress_ended webhook (review P2).
            txNew.executeWithoutResult(status -> jobs.markEnding(job.getEgressId(),
                    "stop:" + reason, toTimestamp(clock.instant())));
        }
    }

    private void stopQuietly(String egressId) {
        try {
            egress.stop(egressId);
        } catch (RuntimeException e) {
            log.warn("egress stop failed (egress={}): {}", egressId, e.getMessage());
        }
    }

    private int nextPulseIndex(long attemptId) {
        return jobs.findRollingNewestFirst(attemptId).stream()
                .mapToInt(MonitoringRecordingJob::getPulseIndex).max().orElse(-1) + 1;
    }

    private S3Output s3Output() {
        return new S3Output(storageProperties.getEgressEndpoint(),
                storageProperties.getAccessKey(), storageProperties.getSecretKey(),
                storageProperties.getBucket(), "us-east-1", true);
    }

    /** "s3://bucket/key", "/key" or plain "key" → storage object key. */
    static String deriveObjectKey(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String key = filename.trim();
        if (key.startsWith("s3://")) {
            int slash = key.indexOf('/', 5);
            return slash > 0 ? key.substring(slash + 1) : null;
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            int schemeEnd = key.indexOf("://") + 3;
            int slash = key.indexOf('/', schemeEnd);
            return slash > 0 ? key.substring(slash + 1) : null;
        }
        return key.startsWith("/") ? key.substring(1) : key;
    }

    private static String mapStatus(String egressStatus) {
        return switch (egressStatus == null ? "" : egressStatus) {
            case "EGRESS_ACTIVE" -> MonitoringRecordingJob.STATUS_ACTIVE;
            case "EGRESS_STARTING" -> MonitoringRecordingJob.STATUS_STARTING;
            case "EGRESS_ENDING" -> MonitoringRecordingJob.STATUS_ENDING;
            case "EGRESS_COMPLETE" -> MonitoringRecordingJob.STATUS_COMPLETE;
            case "EGRESS_ABORTED" -> MonitoringRecordingJob.STATUS_ABORTED;
            case "EGRESS_LIMIT_REACHED" -> MonitoringRecordingJob.STATUS_FAILED;
            default -> MonitoringRecordingJob.STATUS_FAILED;
        };
    }

    private static boolean isTerminal(String jobStatus) {
        return MonitoringRecordingJob.STATUS_COMPLETE.equals(jobStatus)
                || MonitoringRecordingJob.STATUS_FAILED.equals(jobStatus)
                || MonitoringRecordingJob.STATUS_ABORTED.equals(jobStatus);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private String meta(String kind, int pulseIndex, String key, String value) {
        ObjectNode node = json.createObjectNode();
        node.put("kind", kind);
        node.put("pulseIndex", pulseIndex);
        node.put(key, value);
        return node.toString();
    }

    private void recordActivity(long attemptId, long sessionId, ActivityEventType type,
            String metadataJson) {
        try {
            Long userId = bindings.findFirstByAttemptIdOrderByEpochDesc(attemptId)
                    .map(b -> b.getUserId()).orElse(null);
            if (userId != null) {
                // recordServerEvent is MANDATORY-transactional; the
                // coordinator runs post-commit, so open a short new tx.
                txNew.executeWithoutResult(s -> activity.recordServerEvent(
                        attemptId, sessionId, userId, type, clock.instant(), metadataJson));
            }
        } catch (RuntimeException e) {
            log.debug("recording activity write failed (attempt={}): {}", attemptId, e.getMessage());
        }
    }
}
