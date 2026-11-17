package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.evidence.application.EvidencePolicyReader;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.ActivitySummary;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.AiReport;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.AttemptMeta;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.CameraReport;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.EvidenceRow;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.RecordingReport;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.Retention;
import com.quizopia.backend.monitoring.dto.AttemptMonitoringReportResponse.ReviewRow;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringAlertReviewRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P4-E per-attempt monitoring report (issue #96, ADR-010 §2.4).
 *
 * <p>Pure read-time aggregation — one query per section over persisted
 * P4-A..D rows; nothing is precomputed, nothing is inferred:
 *
 * <ul>
 *   <li>Counts keep the three layers distinct: raw detections
 *       (non-quarantined rows), grouped alerts (aggregation windows),
 *       reviewed alerts (DISTINCT alerts with ≥1 review row) — a review row
 *       is never counted as a detection or an alert.</li>
 *   <li>Camera periods come from CAMERA_* activity events (server-confirmed
 *       only); recording gaps reuse the P4-D UI rule (&gt;2s holes between
 *       COMPLETE non-deleted segments).</li>
 *   <li>Evidence rows list state honestly; {@code playbackRef} is the P4-D
 *       mint endpoint — present only for playable states, so EXPIRED/FAILED
 *       never yield dead URLs and every mint stays individually audited.</li>
 *   <li>No read of {@code grades}/{@code grade_items}; the disclaimer is
 *       fixed server-side and cannot be overridden by callers.</li>
 * </ul>
 */
@Service
public class MonitoringReportService {

    public static final String DISCLAIMER = "Monitoring observations only. "
            + "No automated misconduct verdict; human review required.";

    /** Same rule as the P4-D frontend segment-gap derivation. */
    static final double GAP_THRESHOLD_SECONDS = 2.0;

    /** Bounded listing (audit #2): newest N evidence rows / review rows. */
    static final int EVIDENCE_LIMIT = 100;
    static final int REVIEWS_LIMIT = 200;

    private static final String LIMITS_NOTE =
            "Browser-side ONNX inference (CLIENT_MODEL provenance). Detection thresholds are "
                    + "the client hook's documented policy (phone/screen/multi-person at 0.5 "
                    + "confidence, person-absent 3-frame streak — see the frontend AI hook); "
                    + "observations only — never a verdict.";

    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessionRepo;
    private final AttemptRepository attemptRepo;
    private final MonitoringAlertReviewRepository reviewRepo;
    private final EvidencePolicyReader policyReader;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MonitoringReportService(AttemptAuthorizationService auth,
            ExamSessionRepository sessionRepo,
            AttemptRepository attemptRepo,
            MonitoringAlertReviewRepository reviewRepo,
            EvidencePolicyReader policyReader,
            JdbcTemplate jdbc,
            Clock clock) {
        this.auth = auth;
        this.sessionRepo = sessionRepo;
        this.attemptRepo = attemptRepo;
        this.reviewRepo = reviewRepo;
        this.policyReader = policyReader;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttemptMonitoringReportResponse report(Long userId, Long attemptId) {
        var attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        Long sessionId = attempt.getExamSessionId();
        requireTeacherReader(userId, sessionId);

        var mediaPolicy = policyReader.policyOf(sessionId);
        // Legacy sessions may have NO policy row (all-OFF per policyOf) —
        // aggregate columns so the report never 500s on them (audit #1).
        Map<String, Object> policyRow = jdbc.queryForMap(
                "SELECT COALESCE((SELECT monitoring_enabled "
                        + "FROM session_monitoring_policies WHERE exam_session_id = ?), false) AS monitoring_enabled, "
                        + "COALESCE((SELECT ai_detection_enabled "
                        + "FROM session_monitoring_policies WHERE exam_session_id = ?), false) AS ai_detection_enabled, "
                        + "COALESCE((SELECT activity_tracking_enabled "
                        + "FROM session_monitoring_policies WHERE exam_session_id = ?), false) AS activity_tracking_enabled",
                sessionId, sessionId, sessionId);

        Map<String, Object> session = jdbc.queryForMap(
                "SELECT s.title, s.code, u.display_name FROM exam_sessions s "
                        + "JOIN users u ON u.id = ? WHERE s.id = ?", attempt.getUserId(), sessionId);

        Instant windowFrom = attempt.getStartedAt();
        Instant windowTo = attempt.getSubmittedAt() != null
                ? attempt.getSubmittedAt() : Instant.now(clock);
        // F02/F06 share the authoritative window: periods and coverage are
        // both bounded by it.

        return new AttemptMonitoringReportResponse(
                attemptMeta(attemptId, sessionId, attempt, session),
                new AttemptMonitoringReportResponse.Policy(
                        bool(policyRow.get("monitoring_enabled")),
                        bool(policyRow.get("ai_detection_enabled")),
                        bool(policyRow.get("activity_tracking_enabled")),
                        mediaPolicy.snapshotsEnabled(),
                        mediaPolicy.fullRecordingEnabled(),
                        mediaPolicy.clipsEnabled(),
                        mediaPolicy.mediaRetentionDays(),
                        mediaPolicy.activityRetentionDays()),
                new AttemptMonitoringReportResponse.MonitoringWindow(
                        windowFrom, windowTo,
                        Duration.between(windowFrom, windowTo).toMinutes()),
                activitySummary(attemptId),
                cameraReport(attemptId, attempt),
                recordingReport(sessionId, attemptId, mediaPolicy, windowFrom, windowTo),
                aiReport(attemptId),
                evidenceRows(sessionId, attemptId),
                reviewRows(attemptId),
                new Retention(mediaPolicy.mediaRetentionDays(),
                        mediaPolicy.activityRetentionDays(), Instant.now(clock)),
                DISCLAIMER);
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private AttemptMeta attemptMeta(Long attemptId, Long sessionId,
            com.quizopia.backend.attempt.domain.model.Attempt attempt, Map<String, Object> session) {
        return new AttemptMeta(
                attemptId,
                sessionId,
                (String) session.get("title"),
                (String) session.get("code"),
                attempt.getUserId(),
                (String) session.get("display_name"),
                attempt.getAttemptNumber() == null ? 0 : attempt.getAttemptNumber(),
                attempt.getStatus().name(),
                attempt.getStartedAt(),
                attempt.getSubmittedAt(),
                attempt.getDeadlineAt());
    }

    private ActivitySummary activitySummary(Long attemptId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        jdbc.query("""
                SELECT event_type, COUNT(*) AS n FROM attempt_activity_events
                WHERE attempt_id = ? GROUP BY event_type ORDER BY event_type
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> byType.put(rs.getString("event_type"), rs.getLong("n")), attemptId);
        long total = byType.values().stream().mapToLong(Long::longValue).sum();
        long answerAccepted = byType.getOrDefault("ANSWER_ACCEPTED", 0L);
        Long distinctQuestions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT attempt_question_id) FROM attempt_answer_history "
                        + "WHERE attempt_id = ?", Long.class, attemptId);
        List<ActivitySummary.CountByType> counts = byType.entrySet().stream()
                .map(e -> new ActivitySummary.CountByType(e.getKey(), e.getValue()))
                .toList();
        return new ActivitySummary(
                total,
                answerAccepted,
                distinctQuestions == null ? 0 : distinctQuestions,
                new ActivitySummary.BrowserSummary(
                        byType.getOrDefault("TAB_HIDDEN", 0L),
                        byType.getOrDefault("TAB_VISIBLE", 0L),
                        byType.getOrDefault("WINDOW_FOCUS_LOST", 0L),
                        byType.getOrDefault("WINDOW_FOCUS_GAINED", 0L),
                        byType.getOrDefault("PAGE_RELOADED", 0L),
                        byType.getOrDefault("CONNECTION_LOST", 0L)),
                counts);
    }

    private CameraReport cameraReport(Long attemptId,
            com.quizopia.backend.attempt.domain.model.Attempt attempt) {
        // Periods are bounded by the attempt's monitoring window (F02): a
        // report opened days after submission must never imply the camera
        // was monitored through those days. Authoritative end = submittedAt
        // when closed, else the report's now (attempt still live).
        Instant windowStart = attempt.getStartedAt();
        Instant windowEnd = attempt.getSubmittedAt() != null
                ? attempt.getSubmittedAt() : Instant.now(clock);
        List<CameraReport.Period> connected = new ArrayList<>();
        List<CameraReport.Period> disconnected = new ArrayList<>();
        jdbc.query("""
                SELECT event_type, server_received_at FROM attempt_activity_events
                WHERE attempt_id = ? AND source = 'SERVER'
                  AND event_type IN ('CAMERA_CONNECTED','CAMERA_DISCONNECTED',
                                     'CAMERA_RECONNECTED','CAMERA_REVOKED','MONITORING_GAP')
                ORDER BY server_received_at ASC, id ASC
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String type = rs.getString("event_type");
                    Instant at = rs.getTimestamp("server_received_at").toInstant();
                    if ("CAMERA_CONNECTED".equals(type) || "CAMERA_RECONNECTED".equals(type)) {
                        connected.add(new CameraReport.Period(at, null));
                        if (!disconnected.isEmpty()
                                && disconnected.get(disconnected.size() - 1).to() == null) {
                            closeLast(disconnected, at);
                        }
                    } else {
                        disconnected.add(new CameraReport.Period(at, null));
                        if (!connected.isEmpty()
                                && connected.get(connected.size() - 1).to() == null) {
                            closeLast(connected, at);
                        }
                    }
                }, attemptId);
        Long gaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attempt_activity_events "
                        + "WHERE attempt_id = ? AND event_type = 'MONITORING_GAP'",
                Long.class, attemptId);
        String lastEvent = queryLastCameraEvent(attemptId);
        // Close any still-open trailing period at the WINDOW end (F02) —
        // submittedAt for finished attempts, now for a live one.
        if (!connected.isEmpty() && connected.get(connected.size() - 1).to() == null) {
            closeLast(connected, windowEnd);
        }
        if (!disconnected.isEmpty() && disconnected.get(disconnected.size() - 1).to() == null) {
            closeLast(disconnected, windowEnd);
        }
        // Honesty guards: clamp into [windowStart, windowEnd] and drop
        // degenerate/overlapping artifacts (tied or out-of-order events).
        List<CameraReport.Period> sanitizedConnected =
                sanitizePeriods(connected, windowStart, windowEnd);
        List<CameraReport.Period> sanitizedDisconnected =
                sanitizePeriods(disconnected, windowStart, windowEnd);
        return new CameraReport(sanitizedConnected.isEmpty(), sanitizedConnected,
                sanitizedDisconnected, gaps == null ? 0 : gaps, lastEvent);
    }

    /** Clamp to the window, drop non-positive ranges, keep order. */
    private static List<CameraReport.Period> sanitizePeriods(
            List<CameraReport.Period> periods, Instant windowStart, Instant windowEnd) {
        List<CameraReport.Period> out = new ArrayList<>();
        for (CameraReport.Period p : periods) {
            Instant from = p.from().isBefore(windowStart) ? windowStart : p.from();
            Instant to = p.to() != null && p.to().isAfter(windowEnd) ? windowEnd : p.to();
            if (to != null && !to.isAfter(from)) {
                continue; // degenerate (tied events) — not a real period
            }
            out.add(new CameraReport.Period(from, to));
        }
        return out;
    }

    private RecordingReport recordingReport(Long sessionId, Long attemptId,
            EvidencePolicyReader.MediaPolicy policy, Instant windowStart, Instant windowEnd) {
        List<RecordingReport.Segment> segments = new ArrayList<>();
        jdbc.query("""
                SELECT id, kind, status, started_at, ended_at, bytes, duration_ms,
                       object_deleted, expires_at
                FROM monitoring_recording_jobs WHERE attempt_id = ?
                ORDER BY started_at ASC, id ASC
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String status = rs.getString("status");
                    boolean playable = "COMPLETE".equals(status)
                            && !rs.getBoolean("object_deleted")
                            && (rs.getTimestamp("expires_at") == null
                                    || rs.getTimestamp("expires_at").toInstant()
                                            .isAfter(Instant.now(clock)));
                    segments.add(new RecordingReport.Segment(
                            rs.getObject("id").toString(),
                            rs.getString("kind"),
                            status,
                            ts(rs.getTimestamp("started_at")),
                            ts(rs.getTimestamp("ended_at")),
                            rs.getLong("bytes"),
                            rs.getLong("duration_ms"),
                            rs.getBoolean("object_deleted"),
                            ts(rs.getTimestamp("expires_at")),
                            playable ? "/api/exam-sessions/" + sessionId
                                    + "/recordings/" + rs.getObject("id") + "/url" : null));
                }, attemptId);

        // Gaps: >2s holes between consecutive COMPLETE non-deleted segments
        // (the P4-D UI rule), INCLUDING leading/trailing holes of the
        // monitoring window (F06: a 60-minute attempt with one 30s clip must
        // not read as 100%).
        List<RecordingReport.Segment> complete = segments.stream()
                .filter(s -> "COMPLETE".equals(s.status()) && !s.objectDeleted()
                        && s.startedAt() != null)
                .sorted(Comparator.comparing(RecordingReport.Segment::startedAt))
                .toList();
        List<RecordingReport.Gap> gaps = new ArrayList<>();
        // Union of covered intervals clamped to the monitoring window —
        // overlaps between segments never double-count (F06).
        List<long[]> intervals = new ArrayList<>();
        for (var seg : complete) {
            Instant segEnd = seg.endedAt() != null ? seg.endedAt() : seg.startedAt();
            Instant from = seg.startedAt().isBefore(windowStart) ? windowStart : seg.startedAt();
            Instant to = segEnd.isAfter(windowEnd) ? windowEnd : segEnd;
            if (to.isAfter(from)) {
                intervals.add(new long[] {
                        Duration.between(windowStart, from).toMillis(),
                        Duration.between(windowStart, to).toMillis()});
            }
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long coveredMs = 0;
        long[] merged = null;
        for (long[] iv : intervals) {
            if (merged == null || iv[0] > merged[1]) {
                if (merged != null) {
                    coveredMs += merged[1] - merged[0];
                }
                merged = iv;
            } else if (iv[1] > merged[1]) {
                merged[1] = iv[1];
            }
        }
        if (merged != null) {
            coveredMs += merged[1] - merged[0];
        }
        // Gap list over the window (leading + between + trailing holes).
        Instant cursor = windowStart;
        for (var seg : complete) {
            Instant segEnd = seg.endedAt() != null ? seg.endedAt() : seg.startedAt();
            if (seg.startedAt().isAfter(cursor)) {
                addGap(gaps, cursor, seg.startedAt());
            }
            if (segEnd.isAfter(cursor)) {
                cursor = segEnd;
            }
        }
        if (cursor.isBefore(windowEnd)) {
            addGap(gaps, cursor, windowEnd);
        }
        // Attempt coverage = covered union / monitoring window (F06
        // semantics). A zero-length window with any coverage reads as 1.0.
        long windowMs = Duration.between(windowStart, windowEnd).toMillis();
        double coverage = windowMs <= 0
                ? (complete.isEmpty() ? 0.0 : 1.0)
                : Math.max(0.0, Math.min(1.0, coveredMs / (double) windowMs));
        String mode = policy.fullRecordingEnabled() ? "FULL"
                : policy.rollingBufferActive() ? "ROLLING" : "NONE";
        return new RecordingReport(mode, segments, gaps, round2(coverage));
    }

    private AiReport aiReport(Long attemptId) {
        Map<String, Object> det = jdbc.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE quarantined = false) AS raw, "
                        + "COUNT(*) FILTER (WHERE quarantined = true) AS quarantined "
                        + "FROM monitoring_ai_detections WHERE attempt_id = ?", attemptId);
        Map<String, Object> alerts = jdbc.queryForMap(
                "SELECT COUNT(*) AS grouped, "
                        + "COUNT(*) FILTER (WHERE status = 'OPEN') AS open, "
                        + "COUNT(*) FILTER (WHERE status = 'CLOSED') AS closed, "
                        + "COUNT(*) FILTER (WHERE review_status = 'UNREVIEWED') AS unreviewed, "
                        + "COUNT(*) FILTER (WHERE review_status = 'FALSE_POSITIVE') AS fp "
                        + "FROM monitoring_ai_alerts WHERE attempt_id = ?", attemptId);
        long reviewedAlerts = reviewRepo.countDistinctReviewedAlertsOfAttempt(attemptId);

        List<AiReport.ModelRow> models = new ArrayList<>();
        jdbc.query("""
                SELECT model_id, model_version, provenance, MAX(runtime) AS runtime,
                       COUNT(*) AS n
                FROM monitoring_ai_detections WHERE attempt_id = ? AND quarantined = false
                GROUP BY model_id, model_version, provenance
                ORDER BY n DESC
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> models.add(new AiReport.ModelRow(
                        rs.getString("model_id"),
                        rs.getString("model_version"),
                        rs.getString("provenance"),
                        rs.getString("runtime"),
                        rs.getLong("n"))), attemptId);

        return new AiReport(
                num(det.get("raw")),
                num(det.get("quarantined")),
                num(alerts.get("grouped")),
                num(alerts.get("open")),
                num(alerts.get("closed")),
                reviewedAlerts,
                num(alerts.get("unreviewed")),
                num(alerts.get("fp")),
                models,
                LIMITS_NOTE);
    }

    private List<EvidenceRow> evidenceRows(Long sessionId, Long attemptId) {
        List<EvidenceRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT id, kind, status, provenance, captured_at, window_start, window_end,
                       bytes, sha256, expires_at, alert_id
                FROM monitoring_evidence WHERE attempt_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String status = rs.getString("status");
                    boolean playable = "AVAILABLE".equals(status) || "PARTIAL".equals(status);
                    Instant expiresAt = ts(rs.getTimestamp("expires_at"));
                    if (playable && expiresAt != null && !expiresAt.isAfter(Instant.now(clock))) {
                        playable = false;
                    }
                    rows.add(new EvidenceRow(
                            rs.getString("id"),
                            rs.getString("kind"),
                            status,
                            rs.getString("provenance"),
                            ts(rs.getTimestamp("captured_at")),
                            ts(rs.getTimestamp("window_start")),
                            ts(rs.getTimestamp("window_end")),
                            rs.getLong("bytes"),
                            rs.getString("sha256"),
                            expiresAt,
                            (Long) rs.getObject("alert_id"),
                            playable ? "/api/exam-sessions/" + sessionId
                                    + "/evidence/" + rs.getString("id") + "/url" : null));
                }, attemptId, EVIDENCE_LIMIT);
        java.util.Collections.reverse(rows); // chronological display
        return rows;
    }

    private List<ReviewRow> reviewRows(Long attemptId) {
        List<ReviewRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT r.id, r.alert_id, r.action, r.note, u.display_name AS reviewer,
                       r.created_at, r.review_version
                FROM monitoring_alert_reviews r
                JOIN users u ON u.id = r.reviewer_user_id
                WHERE r.alert_id IN (SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?)
                ORDER BY r.created_at DESC, r.id DESC LIMIT ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> rows.add(new ReviewRow(
                        rs.getLong("id"),
                        (Long) rs.getObject("alert_id"),
                        rs.getString("action"),
                        rs.getString("note"),
                        rs.getString("reviewer"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("review_version"))), attemptId, REVIEWS_LIMIT);
        java.util.Collections.reverse(rows); // chronological display
        return rows;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private void addGap(List<RecordingReport.Gap> gaps, Instant from, Instant to) {
        double seconds = Duration.between(from, to).toMillis() / 1000.0;
        if (seconds > GAP_THRESHOLD_SECONDS) {
            gaps.add(new RecordingReport.Gap(from, to, round2(seconds)));
        }
    }

    private void closeLast(List<CameraReport.Period> periods, Instant to) {
        int last = periods.size() - 1;
        CameraReport.Period open = periods.get(last);
        periods.set(last, new CameraReport.Period(open.from(), to));
    }

    @Nullable
    private String queryLastCameraEvent(Long attemptId) {
        List<String> r = jdbc.query(
                "SELECT event_type FROM attempt_activity_events "
                        + "WHERE attempt_id = ? AND source = 'SERVER' "
                        + "AND event_type IN ('CAMERA_CONNECTED','CAMERA_DISCONNECTED',"
                        + "'CAMERA_RECONNECTED','CAMERA_REVOKED') "
                        + "ORDER BY server_received_at DESC, id DESC LIMIT 1",
                (rs, n) -> rs.getString(1), attemptId);
        return r.isEmpty() ? null : r.get(0);
    }

    @Nullable
    private static Instant ts(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean bool(Object value) {
        return value != null && ((Boolean) value);
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
