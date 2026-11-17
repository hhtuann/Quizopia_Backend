package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.evidence.application.EvidencePolicyReader;
import com.quizopia.backend.evidence.domain.MonitoringRecordingJob;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringOverviewResponse;
import com.quizopia.backend.monitoring.dto.MonitoringDashboardDtos.MonitoringParticipantsResponse;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4-E unified teacher dashboard reads (issue #95, ADR-010 §2.3).
 *
 * <p>Read-time aggregation over persisted P4-A..D rows — no new write paths,
 * no projections to keep consistent. Two surfaces behind the standard
 * teacher gate ({@code AI_ALERTS_READ} + session ownership, generic denial):
 *
 * <ul>
 *   <li>{@code overview} — session-level KPI counters, each tied to the
 *       signal that produced it, with an explicit {@code asOf}.</li>
 *   <li>{@code participants} — one row per attempt, signals SEPARATE
 *       (attempt / camera / AI / recording / evidence) so the UI never
 *       derives misconduct from a missing signal. Cursor = attemptId DESC
 *       (numeric precedent of the alert feed).</li>
 * </ul>
 */
@Service
public class MonitoringDashboardService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 50;

    private static final String ATTEMPT_STATUSES = "IN_PROGRESS, SUBMITTED, GRADED";

    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessionRepo;
    private final EvidencePolicyReader policyReader;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MonitoringDashboardService(AttemptAuthorizationService auth,
            ExamSessionRepository sessionRepo,
            EvidencePolicyReader policyReader,
            JdbcTemplate jdbc,
            Clock clock) {
        this.auth = auth;
        this.sessionRepo = sessionRepo;
        this.policyReader = policyReader;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Overview
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MonitoringOverviewResponse overview(Long userId, Long sessionId) {
        requireTeacherReader(userId, sessionId);
        var policy = policyReader.policyOf(sessionId);
        // Legacy sessions may have NO policy row (policyOf treats that as
        // all-OFF) — the overview must not 500 for them.
        Boolean aiFlag = jdbc.queryForObject(
                "SELECT COALESCE((SELECT ai_detection_enabled AND monitoring_enabled "
                        + "FROM session_monitoring_policies WHERE exam_session_id = ?), false)",
                Boolean.class, sessionId);
        boolean aiEnabled = Boolean.TRUE.equals(aiFlag);

        Map<String, Long> attemptCounts = new HashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS n FROM attempts
                WHERE exam_session_id = ? AND status IN ('IN_PROGRESS','SUBMITTED','GRADED')
                GROUP BY status
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            attemptCounts.put(rs.getString("status"), rs.getLong("n"));
        }, sessionId);
        int inProgress = countOf(attemptCounts, "IN_PROGRESS");
        int submitted = countOf(attemptCounts, "SUBMITTED");
        int graded = countOf(attemptCounts, "GRADED");

        Map<String, Integer> cameraStates = cameraStatesByUser(sessionId);
        int connected = cameraStates.getOrDefault("CONNECTED", 0);
        int reconnecting = cameraStates.getOrDefault("RECONNECTING", 0);
        int idle = cameraStates.getOrDefault("IDLE", 0);
        int revoked = cameraStates.getOrDefault("REVOKED", 0);
        int publishing = cameraStates.getOrDefault("PUBLISHING", 0);

        Map<String, Long> alertCounts = new HashMap<>();
        jdbc.query("""
                SELECT status,
                       CASE WHEN review_status = 'UNREVIEWED' THEN 'UNREVIEWED' ELSE 'REVIEWED' END AS r,
                       COUNT(*) AS n
                FROM monitoring_ai_alerts WHERE exam_session_id = ? GROUP BY status, r
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            alertCounts.merge(rs.getString("status") + ":" + rs.getString("r"), rs.getLong("n"),
                    Long::sum);
        }, sessionId);
        int openAlerts = countOf(alertCounts, "OPEN:UNREVIEWED") + countOf(alertCounts, "OPEN:REVIEWED");
        int closedAlerts = countOf(alertCounts, "CLOSED:UNREVIEWED") + countOf(alertCounts, "CLOSED:REVIEWED");
        int unreviewedAlerts = countOf(alertCounts, "OPEN:UNREVIEWED") + countOf(alertCounts, "CLOSED:UNREVIEWED");
        Instant lastDetectionAt = queryInstant(
                "SELECT MAX(observed_at) FROM monitoring_ai_detections d "
                        + "JOIN attempts a ON a.id = d.attempt_id "
                        + "WHERE a.exam_session_id = ? AND d.quarantined = false", sessionId);

        Map<String, Long> jobCounts = new HashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS n FROM monitoring_recording_jobs
                WHERE exam_session_id = ? GROUP BY status
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> jobCounts.put(rs.getString("status"), rs.getLong("n")), sessionId);
        int activeJobs = countOf(jobCounts, MonitoringRecordingJob.STATUS_STARTING)
                + countOf(jobCounts, MonitoringRecordingJob.STATUS_ACTIVE)
                + countOf(jobCounts, MonitoringRecordingJob.STATUS_ENDING);
        int completeJobs = countOf(jobCounts, MonitoringRecordingJob.STATUS_COMPLETE);
        int failedJobs = countOf(jobCounts, MonitoringRecordingJob.STATUS_FAILED)
                + countOf(jobCounts, MonitoringRecordingJob.STATUS_ABORTED);

        Map<String, Long> evidenceCounts = new HashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS n FROM monitoring_evidence
                WHERE exam_session_id = ? GROUP BY status
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> evidenceCounts.put(rs.getString("status"), rs.getLong("n")), sessionId);

        return new MonitoringOverviewResponse(
                sessionId,
                Instant.now(clock),
                policy.monitoringEnabled(),
                new MonitoringOverviewResponse.AttemptsOverview(
                        inProgress + submitted + graded, inProgress, submitted, graded),
                new MonitoringOverviewResponse.CameraOverview(
                        policy.monitoringEnabled(), connected, publishing, reconnecting,
                        idle, revoked),
                new MonitoringOverviewResponse.AiOverview(
                        aiEnabled, openAlerts, closedAlerts, unreviewedAlerts,
                        lastDetectionAt),
                new MonitoringOverviewResponse.RecordingOverview(
                        policy.fullRecordingEnabled(), policy.rollingBufferActive(),
                        activeJobs, completeJobs, failedJobs),
                new MonitoringOverviewResponse.EvidenceOverview(
                        countOf(evidenceCounts, "AVAILABLE"), countOf(evidenceCounts, "PARTIAL"),
                        countOf(evidenceCounts, "PENDING"), countOf(evidenceCounts, "FAILED"),
                        countOf(evidenceCounts, "EXPIRED")));
    }

    // ------------------------------------------------------------------
    // Participants
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MonitoringParticipantsResponse participants(Long userId, Long sessionId,
            String cursor, int limit, String status, String cameraState, String reviewStatus) {
        requireTeacherReader(userId, sessionId);
        Boolean aiEnabled = jdbc.queryForObject(
                "SELECT COALESCE((SELECT ai_detection_enabled AND monitoring_enabled "
                        + "FROM session_monitoring_policies WHERE exam_session_id = ?), false)",
                Boolean.class, sessionId);

        String statusFilter = normalizeEnum(status, "status",
                List.of("IN_PROGRESS", "SUBMITTED", "GRADED"));
        String cameraFilter = normalizeEnum(cameraState, "cameraState",
                List.of("CONNECTED", "RECONNECTING", "IDLE", "REVOKED", "NONE"));
        List<String> reviewFilterValues = new ArrayList<>(MonitoringAiAlert.REVIEW_STATES);
        reviewFilterValues.add("REVIEWED"); // aggregate: no unreviewed alert left
        String reviewFilter = normalizeEnum(reviewStatus, "reviewStatus", reviewFilterValues);
        int pageSize = normalizeLimit(limit);
        Long cursorId = decodeCursor(cursor);

        // F03: EVERY filter runs server-side in the keyset query — a page
        // is only attempts that match, so filtered pagination can never
        // surface a false "no participants" while matches exist later.
        // F07: camera state resolves from the user's LATEST binding only
        // (active_from DESC, id DESC) — historical bindings never override.
        // F04: transport (connection open) vs publishing (verified track)
        // vs presence are separate columns; AI liveness (detections,
        // quarantined, lastDetectionAt) is separate from alert rollups.
        // One lateral query — no per-row N+1.
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.user_id, u.display_name, a.attempt_number, a.status,
                       a.started_at, a.deadline_at, a.submitted_at,
                       last_act.last_activity_at,
                       cam.media_identity, cam.connected_at, cam.last_verified_at,
                       cam.track_sid, cam.active_to, cam.revoked_at, cam.binding_attempt_status,
                       ai.total_alerts, ai.open_alerts, ai.unreviewed_alerts,
                       ai.distinct_reviewed, ai.reviewed_state,
                       det.detections, det.quarantined, det.last_detection_at,
                       rec.active_jobs, rec.complete_jobs, rec.failed_jobs,
                       ev.snapshots, ev.clips
                FROM attempts a
                JOIN users u ON u.id = a.user_id
                LEFT JOIN LATERAL (SELECT MAX(e.server_received_at) AS last_activity_at
                                   FROM attempt_activity_events e
                                   WHERE e.attempt_id = a.id) last_act ON true
                LEFT JOIN LATERAL (
                    SELECT b.media_identity, cc.connected_at, cc.last_verified_at,
                           cc.track_sid, b.active_to, b.revoked_at,
                           ba.status AS binding_attempt_status
                    FROM monitoring_media_bindings b
                    LEFT JOIN monitoring_camera_connections cc
                      ON cc.binding_id = b.id AND cc.disconnected_at IS NULL
                    LEFT JOIN attempts ba ON ba.id = b.attempt_id
                    WHERE b.exam_session_id = a.exam_session_id AND b.user_id = a.user_id
                    ORDER BY b.active_from DESC, b.id DESC
                    LIMIT 1) cam ON true
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS total_alerts,
                           COUNT(*) FILTER (WHERE status = 'OPEN') AS open_alerts,
                           COUNT(*) FILTER (WHERE review_status = 'UNREVIEWED') AS unreviewed_alerts,
                           COUNT(DISTINCT review_status)
                               FILTER (WHERE review_status <> 'UNREVIEWED') AS distinct_reviewed,
                           MIN(review_status)
                               FILTER (WHERE review_status <> 'UNREVIEWED') AS reviewed_state
                    FROM monitoring_ai_alerts al WHERE al.attempt_id = a.id) ai ON true
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE quarantined = false) AS detections,
                           COUNT(*) FILTER (WHERE quarantined = true) AS quarantined,
                           MAX(observed_at) FILTER (WHERE quarantined = false) AS last_detection_at
                    FROM monitoring_ai_detections d WHERE d.attempt_id = a.id) det ON true
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE status IN ('STARTING','ACTIVE','ENDING')) AS active_jobs,
                           COUNT(*) FILTER (WHERE status = 'COMPLETE') AS complete_jobs,
                           COUNT(*) FILTER (WHERE status IN ('FAILED','ABORTED')) AS failed_jobs
                    FROM monitoring_recording_jobs r WHERE r.attempt_id = a.id) rec ON true
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) FILTER (WHERE kind = 'SNAPSHOT') AS snapshots,
                           COUNT(*) FILTER (WHERE kind = 'CLIP') AS clips
                    FROM monitoring_evidence ev2 WHERE ev2.attempt_id = a.id) ev ON true
                WHERE a.exam_session_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        if (statusFilter != null) {
            sql.append(" AND a.status = ?");
            args.add(statusFilter);
        }
        if (cursorId != null) {
            sql.append(" AND a.id < ?");
            args.add(cursorId);
        }
        if (cameraFilter != null) {
            sql.append(" AND ").append(CAMERA_STATE_CASE).append(" = ?");
            args.add(cameraFilter);
        }
        if (reviewFilter != null) {
            if ("REVIEWED".equals(reviewFilter)) {
                sql.append(" AND ").append(REVIEW_AGG_CASE).append(" <> 'UNREVIEWED'");
            } else {
                sql.append(" AND ").append(REVIEW_AGG_CASE).append(" = ?");
                args.add(reviewFilter);
            }
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        args.add(pageSize + 1);

        var policy = policyReader.policyOf(sessionId);
        String mode = policy.fullRecordingEnabled() ? "FULL"
                : policy.rollingBufferActive() ? "ROLLING" : "NONE";
        List<MonitoringParticipantsResponse.Item> page = jdbc.query(sql.toString(),
                (rs, n) -> toItem(rs, mode, Boolean.TRUE.equals(aiEnabled)),
                args.toArray());
        boolean hasNext = page.size() > pageSize;
        List<MonitoringParticipantsResponse.Item> items =
                hasNext ? page.subList(0, pageSize) : page;
        String nextCursor = hasNext && !items.isEmpty()
                ? String.valueOf(items.get(items.size() - 1).attemptId())
                : null;
        return new MonitoringParticipantsResponse(sessionId, items, nextCursor,
                "NOT_AVAILABLE");
    }

    /** Camera state over the latest binding (F07; cameraStatus precedent). */
    private static final String CAMERA_STATE_CASE = """
            CASE WHEN cam.media_identity IS NULL THEN 'NONE'
                 WHEN cam.revoked_at IS NOT NULL THEN 'REVOKED'
                 WHEN cam.connected_at IS NOT NULL THEN 'CONNECTED'
                 WHEN cam.active_to IS NULL
                      AND cam.binding_attempt_status = 'IN_PROGRESS' THEN 'RECONNECTING'
                 ELSE 'IDLE' END""";

    /** Review aggregate (per-state when unanimous, REVIEWED when mixed). */
    private static final String REVIEW_AGG_CASE = """
            CASE WHEN ai.total_alerts IS NULL OR ai.total_alerts = 0
                      OR ai.unreviewed_alerts > 0 THEN 'UNREVIEWED'
                 WHEN ai.distinct_reviewed = 1 THEN ai.reviewed_state
                 ELSE 'REVIEWED' END""";

    private MonitoringParticipantsResponse.Item toItem(java.sql.ResultSet rs,
            String recordingMode, boolean aiEnabled) throws java.sql.SQLException {
        String state;
        if (rs.getObject("media_identity") == null) {
            state = "NONE";
        } else if (rs.getTimestamp("revoked_at") != null) {
            state = "REVOKED";
        } else if (rs.getTimestamp("connected_at") != null) {
            state = "CONNECTED";
        } else if (rs.getTimestamp("active_to") == null
                && "IN_PROGRESS".equals(rs.getString("binding_attempt_status"))) {
            state = "RECONNECTING";
        } else {
            state = "IDLE";
        }
        int totalAlerts = rs.getInt("total_alerts");
        int unreviewedAlerts = rs.getInt("unreviewed_alerts");
        int distinctReviewed = rs.getInt("distinct_reviewed");
        String reviewStatus;
        if (totalAlerts == 0 || unreviewedAlerts > 0) {
            reviewStatus = MonitoringAiAlert.REVIEW_UNREVIEWED;
        } else if (distinctReviewed == 1) {
            reviewStatus = rs.getString("reviewed_state");
        } else {
            reviewStatus = "REVIEWED";
        }
        return new MonitoringParticipantsResponse.Item(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("display_name"),
                rs.getInt("attempt_number"),
                rs.getString("status"),
                ts(rs.getTimestamp("started_at")),
                ts(rs.getTimestamp("deadline_at")),
                ts(rs.getTimestamp("submitted_at")),
                ts(rs.getTimestamp("last_activity_at")),
                new MonitoringParticipantsResponse.Item.CameraState(
                        state,
                        ts(rs.getTimestamp("connected_at")),
                        ts(rs.getTimestamp("last_verified_at")),
                        rs.getString("media_identity"),
                        // F04: publishing = a VERIFIED published track
                        // (track_sid on the open connection); online =
                        // transport connected. Separate honest signals.
                        rs.getString("track_sid") != null,
                        "CONNECTED".equals(state)),
                new MonitoringParticipantsResponse.Item.AiState(
                        aiEnabled,
                        ts(rs.getTimestamp("last_detection_at")),
                        rs.getInt("open_alerts"),
                        totalAlerts,
                        unreviewedAlerts,
                        rs.getLong("detections"),
                        rs.getLong("quarantined")),
                new MonitoringParticipantsResponse.Item.RecordingState(
                        recordingMode,
                        rs.getInt("active_jobs"),
                        rs.getInt("complete_jobs"),
                        rs.getInt("failed_jobs")),
                new MonitoringParticipantsResponse.Item.EvidenceState(
                        rs.getInt("snapshots"),
                        rs.getInt("clips")),
                reviewStatus);
    }

    @Nullable
    private static Instant ts(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /**
     * F07: per-USER camera state from the LATEST binding only
     * (DISTINCT ON ... ORDER BY user, active_from DESC, id DESC) — a
     * historical REVOKED binding can never override a newer CONNECTED one.
     * Returns state name -> user count; also counts publishing (verified
     * track on the open connection — F04).
     */
    private Map<String, Integer> cameraStatesByUser(Long sessionId) {
        Map<String, Integer> states = new HashMap<>();
        jdbc.query("""
                SELECT DISTINCT ON (b.user_id)
                       b.user_id, cc.connected_at, cc.track_sid, b.active_to,
                       b.revoked_at, ba.status AS binding_attempt_status
                FROM monitoring_media_bindings b
                LEFT JOIN monitoring_camera_connections cc
                  ON cc.binding_id = b.id AND cc.disconnected_at IS NULL
                LEFT JOIN attempts ba ON ba.id = b.attempt_id
                WHERE b.exam_session_id = ?
                ORDER BY b.user_id, b.active_from DESC, b.id DESC
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String state;
                    if (rs.getTimestamp("revoked_at") != null) {
                        state = "REVOKED";
                    } else if (rs.getTimestamp("connected_at") != null) {
                        state = "CONNECTED";
                    } else if (rs.getTimestamp("active_to") == null
                            && "IN_PROGRESS".equals(rs.getString("binding_attempt_status"))) {
                        state = "RECONNECTING";
                    } else {
                        state = "IDLE";
                    }
                    states.merge(state, 1, Integer::sum);
                    if ("CONNECTED".equals(state) && rs.getString("track_sid") != null) {
                        states.merge("PUBLISHING", 1, Integer::sum);
                    }
                }, sessionId);
        return states;
    }

    private void requireTeacherReader(Long userId, Long sessionId) {
        try {
            auth.requireLearnerWithPermission(userId, "AI_ALERTS_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        sessionRepo.findById(sessionId)
                .filter(s -> userId.equals(s.getCreatedBy()))
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }

    @Nullable
    private Instant queryInstant(String sql, Object... args) {
        List<Timestamp> r = jdbc.query(sql,
                (rs, n) -> rs.getTimestamp(1), args);
        return r.isEmpty() || r.get(0) == null ? null : r.get(0).toInstant();
    }

    @Nullable
    private Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private static int countOf(Map<String, ? extends Number> counts, String key) {
        Number n = counts.get(key);
        return n == null ? 0 : n.intValue();
    }

    private String normalizeEnum(String value, String field, List<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase();
        if (!allowed.contains(v)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    field + " must be one of " + String.join(", ", allowed));
        }
        return v;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(cursor.trim());
            if (id <= 0) {
                throw new NumberFormatException("not positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_CURSOR_INVALID);
        }
    }
}
