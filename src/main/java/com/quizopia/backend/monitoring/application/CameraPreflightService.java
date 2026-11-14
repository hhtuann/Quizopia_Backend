package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.SessionEligibilityPolicy;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.exception.AttemptErrorCode;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.exam.domain.model.ExamSession;
import com.quizopia.backend.exam.domain.model.ExamSessionStatus;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.exam.repository.SessionMonitoringPolicyRepository;
import com.quizopia.backend.identity.repository.RolePermissionRepository;
import com.quizopia.backend.media.config.LiveKitProperties;
import com.quizopia.backend.media.roomservice.LkParticipant;
import com.quizopia.backend.media.roomservice.LkTrack;
import com.quizopia.backend.media.roomservice.MediaRoomGateway;
import com.quizopia.backend.media.roomservice.TwirpMediaRoomGateway;
import com.quizopia.backend.media.token.LiveKitTokenService;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.MonitoringMediaBinding;
import com.quizopia.backend.monitoring.domain.model.MonitoringPreflight;
import com.quizopia.backend.monitoring.domain.model.PreflightStatus;
import com.quizopia.backend.monitoring.dto.CameraStatusResponse;
import com.quizopia.backend.monitoring.dto.MediaTokenResponse;
import com.quizopia.backend.monitoring.dto.PreflightStartResponse;
import com.quizopia.backend.monitoring.dto.PreflightVerifyResponse;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringMediaBindingRepository;
import com.quizopia.backend.monitoring.repository.MonitoringMediaRoomRepository;
import com.quizopia.backend.monitoring.repository.MonitoringPreflightRepository;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Camera preflight application service (P4-B, issues #84–#87, ADR-008 D2/D3):
 * preflight create/verify with one-time hashed readiness leases, attempt
 * media-token renewal, the teacher camera-status aggregation / viewer token /
 * participant revocation, and the monitored-start lease consumption consumed
 * by {@code AttemptService.createNewAttempt}.
 *
 * <p><b>Media I/O isolation (ADR-08 invariant 6):</b> NO LiveKit call ever
 * happens inside a database transaction. Every mutating flow is split into a
 * transactional part (validation + row changes) and an outside-transaction
 * part (token minting, gateway calls) via {@link TransactionTemplate}
 * boundaries on this non-transactional facade.
 *
 * <p><b>Lease security:</b> the raw readiness lease is 32 random bytes
 * (urlsafe base64, no padding) handed to the client EXACTLY once per verify;
 * only its sha256 hex is persisted. Consumption is one-time: the guarded
 * bulk update VERIFIED→CONSUMED under the preflight row lock (lock order:
 * session → preflight → attempt insert; preflight rows are per-(session,user)
 * and only locked in the start/preflight paths, so no inversion with
 * autosave/submit which lock attempts only).
 *
 * <p><b>Re-verify rotates:</b> only the hash is stored, so a previous raw
 * lease cannot be re-issued; a repeat verify mints a NEW lease and overwrites
 * hash+expiry while the preflight stays VERIFIED.
 *
 * <p><b>Resume never requires camera</b> (ADR-08 D8): the gate lives in the
 * new-attempt path only.
 */
@Service
public class CameraPreflightService {

    private static final Logger log = LoggerFactory.getLogger(CameraPreflightService.class);

    private static final String PERMISSION_PREFLIGHT = "MONITORING_PREFLIGHT";
    private static final String PERMISSION_CAMERA_MANAGE = "MONITORING_CAMERA_MANAGE";

    /** Handle returned by the start gate: the spent lease's binding to attach. */
    public record ConsumedLease(UUID preflightId, UUID bindingId) {
    }

    /** Internal result of the preflight-create transaction. */
    private record CreatedPreflight(UUID preflightId, String mediaIdentity, String roomName,
            Instant expiresAt) {
    }

    private final MonitoringPreflightRepository preflightRepo;
    private final MonitoringMediaBindingRepository bindingRepo;
    private final MonitoringMediaRoomRepository roomRepo;
    private final com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository cameraConnections;
    private final AttemptRepository attemptRepo;
    private final ExamSessionRepository sessionRepo;
    private final SessionMonitoringPolicyRepository monitoringPolicyRepo;
    private final SessionEligibilityPolicy eligibility;
    private final RolePermissionRepository rolePermissions;
    private final RealtimeAuthorizationService realtimeAuthz;
    private final MediaRoomGateway gateway;
    private final LiveKitTokenService tokens;
    private final LiveKitProperties liveKit;
    private final ActivityEventRecorder activityRecorder;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    public CameraPreflightService(MonitoringPreflightRepository preflightRepo,
            MonitoringMediaBindingRepository bindingRepo,
            MonitoringMediaRoomRepository roomRepo,
            com.quizopia.backend.monitoring.repository.MonitoringCameraConnectionRepository cameraConnections,
            AttemptRepository attemptRepo,
            ExamSessionRepository sessionRepo,
            SessionMonitoringPolicyRepository monitoringPolicyRepo,
            SessionEligibilityPolicy eligibility,
            RolePermissionRepository rolePermissions,
            RealtimeAuthorizationService realtimeAuthz,
            MediaRoomGateway gateway,
            LiveKitTokenService tokens,
            LiveKitProperties liveKit,
            ActivityEventRecorder activityRecorder,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            java.time.Clock clock) {
        this.preflightRepo = preflightRepo;
        this.bindingRepo = bindingRepo;
        this.roomRepo = roomRepo;
        this.cameraConnections = cameraConnections;
        this.attemptRepo = attemptRepo;
        this.sessionRepo = sessionRepo;
        this.monitoringPolicyRepo = monitoringPolicyRepo;
        this.eligibility = eligibility;
        this.rolePermissions = rolePermissions;
        this.realtimeAuthz = realtimeAuthz;
        this.gateway = gateway;
        this.tokens = tokens;
        this.liveKit = liveKit;
        this.activityRecorder = activityRecorder;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ============================================================
    // 1. PREFLIGHT CREATE (learner)
    // ============================================================

    /**
     * Creates a camera preflight for the caller on a monitored session:
     * validates the same start-path preconditions (eligibility, OPEN +
     * window, no IN_PROGRESS attempt) under the session row lock, expires the
     * caller's prior ACTIVE preflights, lazily creates the session's opaque
     * media room, and stores a fresh preflight (ACTIVE) with its media
     * identity + unbound binding at the next epoch. The student media token
     * is minted AFTER the transaction commits.
     *
     * <p>Media availability is checked BEFORE any row is written: with the
     * infrastructure disabled, retries must not accumulate room/preflight/
     * binding rows (nothing would clean them up) — the call fails fast with
     * 503 and the database stays untouched.
     */
    public PreflightStartResponse createPreflight(Long userId, Long sessionId) {
        requirePermission(userId, PERMISSION_PREFLIGHT);
        requireInfraEnabled();
        Instant now = clock.instant();
        CreatedPreflight created = tx.execute(status -> {
            // Mirrors startAttempt's validation order; the session row lock
            // is held because the lazy SCHEDULED→OPEN transition may write.
            ExamSession session = sessionRepo.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new AttemptException(AttemptErrorCode.EXAM_SESSION_NOT_FOUND));
            if (!Boolean.TRUE.equals(monitoringPolicyRepo.isMonitoringEnabled(sessionId))) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_APPLICABLE);
            }
            if (!eligibility.isEligible(userId, session)) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_NOT_ELIGIBLE);
            }
            if (session.getStatus() == ExamSessionStatus.SCHEDULED
                    && !now.isBefore(session.getStartsAt()) && !now.isAfter(session.getEndsAt())) {
                session.open(now);
                sessionRepo.saveAndFlush(session);
            }
            if (session.getStatus() != ExamSessionStatus.OPEN) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_SESSION_NOT_OPEN);
            }
            if (now.isBefore(session.getStartsAt()) || now.isAfter(session.getEndsAt())) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_OUTSIDE_WINDOW);
            }
            if (attemptRepo.findByExamSessionIdAndUserIdAndStatus(
                    sessionId, userId, AttemptStatus.IN_PROGRESS).isPresent()) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_ALREADY_ACTIVE);
            }

            // A new preflight supersedes the caller's stale ACTIVE ones
            // (never VERIFIED ones — their leases stay spendable).
            preflightRepo.expireActivePreflights(sessionId, userId,
                    PreflightStatus.ACTIVE, PreflightStatus.EXPIRED);

            String roomName = ensureRoom(sessionId, now);

            UUID preflightId = UUID.randomUUID();
            String mediaIdentity = UUID.randomUUID().toString();
            MonitoringPreflight preflight = MonitoringPreflight.create(
                    preflightId, sessionId, userId, mediaIdentity,
                    now.plus(liveKit.getPreflightTtl()), now);
            preflightRepo.saveAndFlush(preflight);

            int epoch = bindingRepo.findMaxEpoch(sessionId, userId).orElse(0) + 1;
            MonitoringMediaBinding binding = MonitoringMediaBinding.create(
                    UUID.randomUUID(), sessionId, userId, preflightId, mediaIdentity, epoch, now);
            bindingRepo.saveAndFlush(binding);

            return new CreatedPreflight(preflightId, mediaIdentity, roomName, preflight.getExpiresAt());
        });

        // Outside the transaction: no media call ever waits on a DB tx.
        String token = mintStudentTokenOrUnavailable(created.mediaIdentity(), created.roomName());
        return new PreflightStartResponse(created.preflightId(), token,
                clock.instant().plus(liveKit.getMediaTokenTtl()), created.mediaIdentity(),
                liveKit.getWsUrl(), "session", true, created.expiresAt());
    }

    // ============================================================
    // 2. PREFLIGHT VERIFY (learner) — readiness lease
    // ============================================================

    /**
     * Polls the media server for the preflight's published camera track.
     * Returns {@code NOT_PUBLISHED} while the camera is absent/muted (client
     * keeps polling). Once LiveKit shows an active camera track the backend
     * — and only the backend — mints a one-time readiness lease: raw lease to
     * the client, sha256 hash + expiry persisted. A repeat verify while the
     * preflight is still valid ROTATES the lease (the previous raw value is
     * unrecoverable from the hash by construction).
     */
    public PreflightVerifyResponse verifyPreflight(Long userId, Long sessionId, UUID preflightId) {
        requirePermission(userId, PERMISSION_PREFLIGHT);
        Instant now = clock.instant();

        VerifiedPreflight verified = tx.execute(status -> {
            MonitoringPreflight preflight = preflightRepo.findById(preflightId)
                    .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_FOUND));
            if (!preflight.getExamSessionId().equals(sessionId)) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_FOUND);
            }
            if (!preflight.getUserId().equals(userId)) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
            }
            requireVerifiable(preflight, now);
            String roomName = roomRepo.findById(sessionId)
                    .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE))
                    .getRoomName();
            return new VerifiedPreflight(preflight.getId(), preflight.getMediaIdentity(), roomName);
        });

        Optional<LkParticipant> participant = findParticipantOrUnavailable(verified.roomName(),
                verified.mediaIdentity());
        if (participant.isEmpty() || !participant.get().hasActiveCamera()) {
            return PreflightVerifyResponse.notPublished();
        }
        String trackSid = activeCameraTrackSid(participant.get());

        // Second transaction: grant (or rotate) the lease after the media
        // confirmation. The transition itself is status-guarded in SQL — a
        // preflight that meanwhile reached a terminal state (CONSUMED by a
        // start, REVOKED by a teacher, EXPIRED) can never be resurrected to
        // VERIFIED by this unlocked re-read.
        return tx.execute(status -> {
            MonitoringPreflight preflight = preflightRepo.findById(verified.preflightId())
                    .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_FOUND));
            requireVerifiable(preflight, clock.instant());
            String rawLease = newRawLease();
            Instant leaseExpiresAt = clock.instant().plus(liveKit.getReadinessLeaseTtl());
            int granted = preflightRepo.markVerifiedGuarded(
                    verified.preflightId(),
                    java.util.List.of(PreflightStatus.ACTIVE, PreflightStatus.VERIFIED),
                    PreflightStatus.VERIFIED,
                    sha256Hex(rawLease), leaseExpiresAt, trackSid);
            if (granted != 1) {
                PreflightStatus current = preflightRepo.findById(verified.preflightId())
                        .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_FOUND))
                        .getStatus();
                if (current == PreflightStatus.CONSUMED) {
                    throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_USED);
                }
                throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_EXPIRED);
            }
            return PreflightVerifyResponse.ready(rawLease, leaseExpiresAt, trackSid);
        });
    }

    private record VerifiedPreflight(UUID preflightId, String mediaIdentity, String roomName) {
    }

    /**
     * A preflight is verifiable while ACTIVE/VERIFIED and not yet expired.
     * A CONSUMED preflight (its lease was spent by a start) maps to the more
     * precise USED — terminal states are never resurrected.
     */
    private static void requireVerifiable(MonitoringPreflight preflight, Instant now) {
        if (preflight.getStatus() == PreflightStatus.CONSUMED) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_USED);
        }
        boolean statusOk = preflight.getStatus() == PreflightStatus.ACTIVE
                || preflight.getStatus() == PreflightStatus.VERIFIED;
        if (!statusOk || !preflight.getExpiresAt().isAfter(now)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_EXPIRED);
        }
    }

    // ============================================================
    // 3. ATTEMPT MEDIA TOKEN (learner renewal)
    // ============================================================

    /**
     * Mints a fresh media token for the caller's IN_PROGRESS monitored
     * attempt — the renewal/reconnect path (same media identity, so the
     * published track and its server-side binding survive). Revoked binding
     * → 403; no active binding (e.g. housekeeping closed it) → 409: the
     * student reloads and RESUMES — camera is never required to resume.
     */
    public MediaTokenResponse attemptMediaToken(Long userId, Long attemptId) {
        requirePermission(userId, PERMISSION_PREFLIGHT);
        Instant now = clock.instant();

        TokenContext context = tx.execute(status -> {
            Attempt attempt = attemptRepo.findById(attemptId)
                    .orElseThrow(() -> new AttemptException(AttemptErrorCode.ATTEMPT_NOT_FOUND));
            if (!attempt.getUserId().equals(userId)) {
                throw new AttemptException(AttemptErrorCode.ATTEMPT_ACCESS_DENIED);
            }
            Long sessionId = attempt.getExamSessionId();
            boolean monitored = Boolean.TRUE
                    .equals(monitoringPolicyRepo.isMonitoringEnabled(sessionId));
            if (attempt.getStatus() != AttemptStatus.IN_PROGRESS || !monitored) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_NOT_APPLICABLE);
            }
            MonitoringMediaBinding binding = bindingRepo
                    .findFirstByAttemptIdOrderByEpochDesc(attemptId)
                    .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_BINDING_MISSING));
            if (binding.getRevokedAt() != null) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_CAMERA_REVOKED);
            }
            if (binding.getActiveTo() != null) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_BINDING_MISSING);
            }
            String roomName = roomRepo.findById(sessionId)
                    .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE))
                    .getRoomName();
            return new TokenContext(binding.getMediaIdentity(), roomName);
        });

        return mintMediaTokenOrUnavailable(context.mediaIdentity(), context.roomName(), now);
    }

    private record TokenContext(String mediaIdentity, String roomName) {
    }

    // ============================================================
    // 4. CAMERA STATUS (teacher aggregation)
    // ============================================================

    /**
     * Server-truth snapshot for the teacher camera grid: every binding of
     * the session joined with its open camera connection (webhook/reconcile
     * maintained), the learner's display name and the bound attempt's
     * status. Read-only.
     */
    @Transactional(readOnly = true)
    public CameraStatusResponse cameraStatus(Long userId, Long sessionId) {
        requireTeacherGate(userId, sessionId);

        String sql = "SELECT b.media_identity, b.user_id, u.display_name, b.attempt_id, "
                + "a.status AS attempt_status, b.active_to, b.revoked_at, "
                + "cc.connected_at, cc.last_verified_at "
                + "FROM monitoring_media_bindings b "
                + "JOIN users u ON u.id = b.user_id "
                + "LEFT JOIN attempts a ON a.id = b.attempt_id "
                + "LEFT JOIN monitoring_camera_connections cc "
                + "  ON cc.binding_id = b.id AND cc.disconnected_at IS NULL "
                + "WHERE b.exam_session_id = ? "
                + "ORDER BY b.active_from ASC, b.id ASC";
        List<CameraStatusResponse.Participant> participants = jdbc.query(sql, (rs, n) -> {
            Instant connectedSince = rs.getTimestamp("connected_at") != null
                    ? rs.getTimestamp("connected_at").toInstant() : null;
            Instant lastVerifiedAt = rs.getTimestamp("last_verified_at") != null
                    ? rs.getTimestamp("last_verified_at").toInstant() : null;
            Long attemptId = rs.getObject("attempt_id") == null ? null : rs.getLong("attempt_id");
            boolean revoked = rs.getTimestamp("revoked_at") != null;
            boolean active = rs.getTimestamp("active_to") == null;
            String attemptStatus = rs.getString("attempt_status");
            String state;
            if (revoked) {
                state = CameraStatusResponse.STATE_REVOKED;
            } else if (connectedSince != null) {
                state = CameraStatusResponse.STATE_CONNECTED;
            } else if (active && "IN_PROGRESS".equals(attemptStatus)) {
                state = CameraStatusResponse.STATE_RECONNECTING;
            } else {
                state = CameraStatusResponse.STATE_IDLE;
            }
            return new CameraStatusResponse.Participant(
                    rs.getString("media_identity"), rs.getLong("user_id"),
                    rs.getString("display_name"), attemptId, state,
                    connectedSince, lastVerifiedAt);
        }, sessionId);
        return new CameraStatusResponse("session", participants);
    }

    // ============================================================
    // 5. TEACHER MEDIA TOKEN
    // ============================================================

    /**
     * Teacher viewer token for the session room (subscribe-only, identity
     * {@code teacher-<userId>}); the room row is lazily created on first
     * teacher access so a grid can open before any student preflight ran.
     * The media availability check precedes the room upsert so a disabled
     * infrastructure never writes rows on retry.
     */
    public MediaTokenResponse teacherMediaToken(Long userId, Long sessionId) {
        requireTeacherGate(userId, sessionId);
        requireInfraEnabled();
        Instant now = clock.instant();
        String roomName = tx.execute(status -> ensureRoom(sessionId, now));
        return mintTeacherTokenOrUnavailable(userId, roomName, now);
    }

    // ============================================================
    // 6. PARTICIPANT REVOKE (teacher)
    // ============================================================

    /**
     * Revokes a participant's camera binding: closes the binding
     * (active_to + revoked_at) AND its open camera-connection period
     * (close_reason 'REVOKED' — a revoked binding must never aggregate as
     * CONNECTED), flips its preflight to REVOKED, records the
     * server-confirmed CAMERA_REVOKED activity event on the bound IN_PROGRESS
     * attempt, and — after commit — removes the participant from the media
     * room (best-effort; a failed kick is logged, the DB state stands).
     */
    public void revokeParticipant(Long userId, Long sessionId, String mediaIdentity) {
        requireTeacherGate(userId, sessionId);
        Instant now = clock.instant();

        Optional<String> roomName = tx.execute(status -> {
            List<MonitoringMediaBinding> bindings = bindingRepo
                    .findByExamSessionIdAndMediaIdentityOrderByEpochDesc(sessionId, mediaIdentity);
            if (bindings.isEmpty()) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_BINDING_MISSING);
            }
            MonitoringMediaBinding binding = bindings.get(0);
            if (binding.getRevokedAt() != null) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_CAMERA_ALREADY_REVOKED);
            }
            binding.markRevoked(now);
            bindingRepo.saveAndFlush(binding);
            preflightRepo.findById(binding.getPreflightId()).ifPresent(MonitoringPreflight::markRevoked);

            // A revoked binding must not aggregate as CONNECTED: close its
            // open camera-connection period in the SAME transaction.
            cameraConnections.closeOpenRow(binding.getId(), "REVOKED", Timestamp.from(now));
            if (binding.getAttemptId() != null) {
                attemptRepo.findById(binding.getAttemptId())
                        .filter(a -> a.getStatus() == AttemptStatus.IN_PROGRESS)
                        .ifPresent(a -> {
                            ObjectNode metadata = JsonNodeFactory.instance.objectNode();
                            metadata.put("mediaIdentity", mediaIdentity);
                            activityRecorder.recordServerEvent(a.getId(), sessionId, a.getUserId(),
                                    ActivityEventType.CAMERA_REVOKED, now, metadata.toString());
                        });
            }
            return roomRepo.findById(sessionId).map(r -> r.getRoomName());
        });

        // Outside the transaction: best-effort kick.
        if (roomName.isPresent() && liveKit.isEnabled()) {
            try {
                gateway.removeParticipant(roomName.get(), mediaIdentity);
            } catch (RuntimeException e) {
                log.warn("camera revoke: removing participant from media room failed "
                        + "(binding already revoked): sessionId={}, identity={}", sessionId, mediaIdentity, e);
            }
        }
    }

    // ============================================================
    // MONITORED START GATE (called by AttemptService.createNewAttempt)
    // ============================================================

    /**
     * Consumes a readiness lease for a NEW attempt on a monitored session.
     * Joins the caller's (start) transaction — the session row lock is
     * already held; lock order is session → preflight (row lock below) →
     * attempt insert.
     *
     * <p>Order of checks: unknown hash → INVALID; wrong session/user →
     * INVALID; already CONSUMED → USED; not VERIFIED or lease past expiry →
     * EXPIRED; guarded atomic VERIFIED→CONSUMED (0 rows → USED — concurrent
     * double spend); binding must exist, be unbound and active → else
     * INVALID.
     *
     * @param rawLease the client-held raw lease (never persisted)
     * @return handle for the subsequent {@link #attachAttemptToBinding}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumedLease consumeLeaseForNewAttempt(Long sessionId, Long userId, String rawLease, Instant now) {
        String leaseHash = sha256Hex(rawLease);
        MonitoringPreflight preflight = preflightRepo.findByLeaseHashForUpdate(leaseHash)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_INVALID));
        if (!preflight.getExamSessionId().equals(sessionId) || !preflight.getUserId().equals(userId)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_INVALID);
        }
        if (preflight.getStatus() == PreflightStatus.CONSUMED) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_USED);
        }
        if (preflight.getStatus() != PreflightStatus.VERIFIED
                || preflight.getLeaseExpiresAt() == null
                || preflight.getLeaseExpiresAt().isBefore(now)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_EXPIRED);
        }
        int spent = preflightRepo.consumeVerified(preflight.getId(),
                PreflightStatus.VERIFIED, PreflightStatus.CONSUMED, now);
        if (spent != 1) {
            // Lost the one-time spend race.
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_USED);
        }
        MonitoringMediaBinding binding = bindingRepo.findByPreflightId(preflight.getId())
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_INVALID));
        if (binding.getAttemptId() != null || binding.getActiveTo() != null
                || binding.getRevokedAt() != null) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_INVALID);
        }
        return new ConsumedLease(preflight.getId(), binding.getId());
    }

    /**
     * Binds the consumed lease's binding to the freshly created attempt —
     * called in the SAME start transaction right after the attempt row is
     * persisted, so the lease spend, the binding and the attempt commit or
     * roll back atomically.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachAttemptToBinding(UUID bindingId, Long attemptId) {
        MonitoringMediaBinding binding = bindingRepo.findById(bindingId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_PREFLIGHT_INVALID));
        binding.markBound(attemptId);
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Lazy opaque room per session: insert-if-absent, then read back. */
    private String ensureRoom(Long sessionId, Instant now) {
        String candidate = "s-" + UUID.randomUUID().toString().replace("-", "");
        roomRepo.insertIgnore(sessionId, candidate, Timestamp.from(now));
        return roomRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("monitoring media room vanished mid-transaction"))
                .getRoomName();
    }

    /** DB-effective permission check (deny-by-default, no JWT-claim trust). */
    private void requirePermission(Long userId, String permissionCode) {
        if (!rolePermissions.findEffectivePermissionCodesByUserId(userId, clock.instant())
                .contains(permissionCode)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
    }

    /**
     * Teacher camera gate: effective MONITORING_CAMERA_MANAGE permission AND
     * the exact STOMP-topic ownership rule (effective EXAM_SESSION_MONITOR +
     * session owner, generic denial — no existence leak).
     */
    private void requireTeacherGate(Long userId, Long sessionId) {
        requirePermission(userId, PERMISSION_CAMERA_MANAGE);
        try {
            realtimeAuthz.authorizeTeacherTopic(userId, sessionId);
        } catch (RealtimeAuthorizationException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
    }

    private String mintStudentTokenOrUnavailable(String mediaIdentity, String roomName) {
        requireInfraEnabled();
        return tokens.mintStudentToken(mediaIdentity, roomName, clock.instant());
    }

    private MediaTokenResponse mintMediaTokenOrUnavailable(String mediaIdentity, String roomName, Instant now) {
        requireInfraEnabled();
        return new MediaTokenResponse(tokens.mintStudentToken(mediaIdentity, roomName, now),
                now.plus(liveKit.getMediaTokenTtl()), mediaIdentity, liveKit.getWsUrl(), "session");
    }

    private MediaTokenResponse mintTeacherTokenOrUnavailable(Long userId, String roomName, Instant now) {
        requireInfraEnabled();
        return new MediaTokenResponse(tokens.mintTeacherToken(userId, roomName, now),
                now.plus(liveKit.getMediaTokenTtl()), "teacher-" + userId,
                liveKit.getWsUrl(), "session");
    }

    private void requireInfraEnabled() {
        if (!liveKit.isEnabled()) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE);
        }
    }

    /** Gateway lookup outside any transaction; infra errors map to 503. */
    private Optional<LkParticipant> findParticipantOrUnavailable(String roomName, String mediaIdentity) {
        requireInfraEnabled();
        try {
            return gateway.findParticipant(roomName, mediaIdentity);
        } catch (TwirpMediaRoomGateway.LiveKitUnavailableException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_INFRA_UNAVAILABLE);
        }
    }

    /** Sid of the first unmuted VIDEO/CAMERA track; callers check presence first. */
    private static String activeCameraTrackSid(LkParticipant participant) {
        return participant.tracks().stream()
                .filter(t -> "VIDEO".equals(t.type()) && "CAMERA".equals(t.source()) && !t.muted())
                .map(LkTrack::sid)
                .findFirst()
                .orElse(null);
    }

    /** 32 random bytes, urlsafe base64 without padding (43 chars). */
    private static String newRawLease() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** sha256 hex (lowercase, 64 chars) — the only persisted lease form. */
    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
