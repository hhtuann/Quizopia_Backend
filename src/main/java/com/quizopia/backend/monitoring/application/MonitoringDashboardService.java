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
                "SELECT COALESCE((SELECT ai_detection_enabled "
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
        int connected = 0;
        int reconnecting = 0;
        int idle = 0;
        int revoked = 0;
        for (int state : cameraStates.values()) {
            switch (state) {
                case 1 -> connected++;
                case 2 -> reconnecting++;
                case 3 -> revoked++;
                default -> idle++;
            }
        }

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
                        policy.monitoringEnabled(), connected, reconnecting, idle, revoked),
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
        var policy = policyReader.policyOf(sessionId);

        String statusFilter = normalizeEnum(status, "status",
                List.of("IN_PROGRESS", "SUBMITTED", "GRADED"));
        String cameraFilter = normalizeEnum(cameraState, "cameraState",
                List.of("CONNECTED", "RECONNECTING", "IDLE", "REVOKED", "NONE"));
        List<String> reviewFilterValues = new ArrayList<>(MonitoringAiAlert.REVIEW_STATES);
        reviewFilterValues.add("REVIEWED"); // aggregate: no unreviewed alert left
        String reviewFilter = normalizeEnum(reviewStatus, "reviewStatus", reviewFilterValues);
        int pageSize = normalizeLimit(limit);
        Long cursorId = decodeCursor(cursor);

        // Page of attempts (attemptId DESC keyset) — the join anchor.
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.user_id, u.display_name, a.attempt_number, a.status,
                       a.started_at, a.deadline_at, a.submitted_at
                FROM attempts a JOIN users u ON u.id = a.user_id
                WHERE a.exam_session_id = ?
                """);
        if (statusFilter != null) {
            sql.append(" AND a.status = ?");
        }
        if (cursorId != null) {
            sql.append(" AND a.id < ?");
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        if (statusFilter != null) {
            args.add(statusFilter);
        }
        if (cursorId != null) {
            args.add(cursorId);
        }
        args.add(pageSize + 1);
        List<Long> attemptIds = jdbc.query(sql.toString(),
                (rs, n) -> rs.getLong("id"), args.toArray());
        boolean hasNext = attemptIds.size() > pageSize;
        List<Long> page = hasNext ? attemptIds.subList(0, pageSize) : attemptIds;

        List<MonitoringParticipantsResponse.Item> items = new ArrayList<>();
        for (Long attemptId : page) {
            items.add(loadItem(attemptId, policy));
        }

        // Post-filters narrow within the page (deterministic under the
        // attemptId cursor — filters never reorder, they only drop rows).
        List<MonitoringParticipantsResponse.Item> filtered = items.stream()
                .filter(i -> cameraFilter == null || i.camera().state().equals(cameraFilter))
                .filter(i -> reviewFilter == null || matchesReview(i.reviewStatus(), reviewFilter))
                .toList();

        String nextCursor = hasNext && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1))
                : null;
        return new MonitoringParticipantsResponse(sessionId, filtered, nextCursor);
    }

    private MonitoringParticipantsResponse.Item loadItem(Long attemptId,
            EvidencePolicyReader.MediaPolicy policy) {
        Map<String, Object> attempt = jdbc.queryForMap(
                "SELECT a.id, a.user_id, u.display_name, a.attempt_number, a.status, "
                        + "a.started_at, a.deadline_at, a.submitted_at "
                        + "FROM attempts a JOIN users u ON u.id = a.user_id WHERE a.id = ?",
                attemptId);
        Long sessionUserId = (Long) attempt.get("user_id");

        // Camera — latest binding of the attempt's user (per-user view; the
        // per-binding grid stays /monitoring/camera-status). NO binding at
        // all is the honest NONE state, distinct from IDLE (bound, offline).
        List<Map<String, Object>> camRows = jdbc.queryForList(
                "SELECT b.media_identity, cc.connected_at, cc.last_verified_at, "
                        + "b.active_to, b.revoked_at, ba.status AS binding_attempt_status "
                        + "FROM monitoring_media_bindings b "
                        + "LEFT JOIN monitoring_camera_connections cc "
                        + "  ON cc.binding_id = b.id AND cc.disconnected_at IS NULL "
                        + "LEFT JOIN attempts ba ON ba.id = b.attempt_id "
                        + "WHERE b.exam_session_id = (SELECT exam_session_id FROM attempts WHERE id = ?) "
                        + "  AND b.user_id = ? "
                        + "ORDER BY b.active_from DESC, b.id DESC LIMIT 1",
                attemptId, sessionUserId);
        Map<String, Object> cam = camRows.isEmpty() ? Map.of() : camRows.get(0);
        Instant connectedSince = instant(cam.get("connected_at"));
        Instant lastVerifiedAt = instant(cam.get("last_verified_at"));
        String mediaIdentity = (String) cam.get("media_identity");
        String state;
        if (cam.isEmpty()) {
            state = "NONE";
        } else if (cam.get("revoked_at") != null) {
            state = "REVOKED";
        } else if (connectedSince != null) {
            state = "CONNECTED";
        } else if (cam.get("active_to") == null
                // The BINDING's attempt drives reconnection, not the roster
                // row's attempt (max_attempts > 1 can diverge — cameraStatus
                // precedent).
                && "IN_PROGRESS".equals(cam.get("binding_attempt_status"))) {
            state = "RECONNECTING";
        } else {
            state = "IDLE";
        }

        Map<String, Object> ai = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COUNT(*) FILTER (WHERE status = 'OPEN') AS open, "
                        + "COUNT(*) FILTER (WHERE review_status = 'UNREVIEWED') AS unreviewed, "
                        + "COUNT(DISTINCT review_status) FILTER ("
                        + "    WHERE review_status <> 'UNREVIEWED') AS distinct_reviewed, "
                        + "MIN(review_status) FILTER ("
                        + "    WHERE review_status <> 'UNREVIEWED') AS reviewed_state "
                        + "FROM monitoring_ai_alerts WHERE attempt_id = ?", attemptId);
        Instant lastDetectionAt = queryInstant(
                "SELECT MAX(observed_at) FROM monitoring_ai_detections "
                        + "WHERE attempt_id = ? AND quarantined = false", attemptId);
        int totalAlerts = ((Number) ai.get("total")).intValue();
        int unreviewedAlerts = ((Number) ai.get("unreviewed")).intValue();
        int distinctReviewed = ai.get("distinct_reviewed") == null
                ? 0 : ((Number) ai.get("distinct_reviewed")).intValue();
        String reviewedState = (String) ai.get("reviewed_state");
        // Aggregate semantics (design §3.2): UNREVIEWED while ANY alert is
        // unreviewed (or none exist); otherwise the single shared review
        // state, or REVIEWED when the alerts carry mixed states.
        String reviewStatus;
        if (totalAlerts == 0 || unreviewedAlerts > 0) {
            reviewStatus = MonitoringAiAlert.REVIEW_UNREVIEWED;
        } else if (distinctReviewed == 1) {
            reviewStatus = reviewedState;
        } else {
            reviewStatus = "REVIEWED";
        }

        Map<String, Object> jobs = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE status IN ('STARTING','ACTIVE','ENDING')) AS active, "
                        + "COUNT(*) FILTER (WHERE status = 'COMPLETE') AS complete, "
                        + "COUNT(*) FILTER (WHERE status IN ('FAILED','ABORTED')) AS failed "
                        + "FROM monitoring_recording_jobs WHERE attempt_id = ?", attemptId);
        String mode = policy.fullRecordingEnabled() ? "FULL"
                : policy.rollingBufferActive() ? "ROLLING" : "NONE";

        Map<String, Object> evidence = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE kind = 'SNAPSHOT') AS snapshots, "
                        + "COUNT(*) FILTER (WHERE kind = 'CLIP') AS clips "
                        + "FROM monitoring_evidence WHERE attempt_id = ?", attemptId);

        Instant lastActivityAt = queryInstant(
                "SELECT MAX(server_received_at) FROM attempt_activity_events "
                        + "WHERE attempt_id = ?", attemptId);

        return new MonitoringParticipantsResponse.Item(
                attemptId,
                sessionUserId,
                (String) attempt.get("display_name"),
                ((Number) attempt.get("attempt_number")).intValue(),
                (String) attempt.get("status"),
                instant(attempt.get("started_at")),
                instant(attempt.get("deadline_at")),
                instant(attempt.get("submitted_at")),
                lastActivityAt,
                new MonitoringParticipantsResponse.Item.CameraState(
                        state, connectedSince, lastVerifiedAt, mediaIdentity),
                new MonitoringParticipantsResponse.Item.AiState(
                        lastDetectionAt,
                        ((Number) ai.get("open")).intValue(),
                        totalAlerts,
                        unreviewedAlerts),
                new MonitoringParticipantsResponse.Item.RecordingState(
                        mode,
                        ((Number) jobs.get("active")).intValue(),
                        ((Number) jobs.get("complete")).intValue(),
                        ((Number) jobs.get("failed")).intValue()),
                new MonitoringParticipantsResponse.Item.EvidenceState(
                        ((Number) evidence.get("snapshots")).intValue(),
                        ((Number) evidence.get("clips")).intValue()),
                reviewStatus);
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /**
     * REVIEWED is the aggregate wildcard (every alert reviewed, any state);
     * the other values match the exact aggregate state.
     */
    private static boolean matchesReview(String aggregate, String filter) {
        if ("REVIEWED".equals(filter)) {
            return !MonitoringAiAlert.REVIEW_UNREVIEWED.equals(aggregate);
        }
        return aggregate.equals(filter);
    }

    /** Per-USER camera state (1 CONNECTED, 2 RECONNECTING, 3 REVOKED, 0 IDLE). */
    private Map<String, Integer> cameraStatesByUser(Long sessionId) {
        Map<String, Integer> states = new HashMap<>();
        jdbc.query("""
                SELECT b.user_id, b.media_identity,
                       cc.connected_at, b.active_to, b.revoked_at, a.status AS attempt_status
                FROM monitoring_media_bindings b
                LEFT JOIN monitoring_camera_connections cc
                  ON cc.binding_id = b.id AND cc.disconnected_at IS NULL
                LEFT JOIN attempts a ON a.id = b.attempt_id
                WHERE b.exam_session_id = ?
                ORDER BY b.active_from ASC, b.id ASC
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String key = rs.getString("user_id");
                    int state;
                    if (rs.getTimestamp("revoked_at") != null) {
                        state = 3;
                    } else if (rs.getTimestamp("connected_at") != null) {
                        state = 1;
                    } else if (rs.getTimestamp("active_to") == null
                            && "IN_PROGRESS".equals(rs.getString("attempt_status"))) {
                        state = 2;
                    } else {
                        state = 0;
                    }
                    states.merge(key, state, Math::max);
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
