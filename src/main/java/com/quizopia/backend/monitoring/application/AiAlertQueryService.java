package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.dto.AiAlertPageResponse;
import com.quizopia.backend.monitoring.dto.AiAlertPageResponse.Item;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Teacher-side alert backfill (P4-C, issue #90, CONTRACT §5):
 * {@code GET /api/exam-sessions/{sessionId}/ai-alerts} — cursor-paged
 * newest-first (cursor = last alertId), newest window first, with the
 * student displayName joined in.
 *
 * <p><b>Authorization (teacher-only):</b> the caller must hold
 * {@code AI_ALERTS_READ} AND own the session (createdBy) — a generic 403
 * denial with no existence leak, mirroring the monitoring gates. Students
 * have no read path at all (the permission is TEACHER-seeded by V38).
 *
 * <p>Alerts are observations (CONTRACT §8): the feed powers the teacher
 * panel; the STOMP activity ping for AI_ALERT_RAISED/AI_ALERT_UPDATED
 * rides the EXISTING session topic and only triggers a refetch — this
 * durable REST feed is the source of truth on reconnect.
 */
@Service
public class AiAlertQueryService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 50;

    private static final String SELECT_PAGE = """
            SELECT a.id, a.attempt_id, a.user_id, u.display_name AS student_name,
                   a.alert_type, a.status, a.first_detected_at, a.last_detected_at,
                   a.detection_count, a.max_confidence, a.model_id, a.model_version,
                   a.provenance, a.latest_payload,
                   -- Boxes of the detection that OPENED the window (the frame
                   -- the P4-D evidence snapshot shows). Derived, not stored:
                   -- the opening detection is the accepted one of the alert's
                   -- type observed exactly at first_detected_at
                   -- (ix_ai_detections_attempt_observed).
                   (SELECT d.payload::text
                      FROM monitoring_ai_detections d
                     WHERE d.attempt_id = a.attempt_id
                       AND d.observed_at = a.first_detected_at
                       AND NOT d.quarantined
                       AND d.event_type = CASE a.alert_type
                               WHEN 'PHONE_DETECTED' THEN 'PHONE_OBSERVED'
                               WHEN 'SCREEN_DETECTED' THEN 'SCREEN_OBSERVED'
                               WHEN 'MULTIPLE_PERSONS_DETECTED' THEN 'MULTIPLE_PERSONS_OBSERVED'
                               WHEN 'PERSON_NOT_VISIBLE' THEN 'PERSON_NOT_OBSERVED'
                               WHEN 'LOOKING_AWAY' THEN 'LOOKING_AWAY_OBSERVED'
                           END
                     ORDER BY d.id
                     LIMIT 1) AS first_payload
            FROM monitoring_ai_alerts a
            JOIN users u ON u.id = a.user_id
            WHERE a.exam_session_id = ?
            """;

    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessionRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AiAlertQueryService(AttemptAuthorizationService auth,
            ExamSessionRepository sessionRepo,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.auth = auth;
        this.sessionRepo = sessionRepo;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AiAlertPageResponse alerts(Long userId, Long sessionId, String cursor,
            int limit, String status) {
        requireTeacherReader(userId, sessionId);

        String statusFilter = normalizeStatus(status);
        int pageSize = normalizeLimit(limit);
        Long cursorId = decodeCursor(cursor);

        StringBuilder sql = new StringBuilder(SELECT_PAGE);
        if (statusFilter != null) {
            sql.append(" AND a.status = ?");
        }
        if (cursorId != null) {
            sql.append(" AND a.id < ?");
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        // fetch one extra row to detect whether another page follows

        Object[] args = buildArgs(sessionId, statusFilter, cursorId, pageSize + 1);
        List<Item> page = jdbc.query(sql.toString(), ALERT_ROW_MAPPER, args);
        boolean hasNext = page.size() > pageSize;
        List<Item> items = hasNext ? page.subList(0, pageSize) : page;
        String nextCursor = hasNext && !items.isEmpty()
                ? String.valueOf(items.get(items.size() - 1).alertId())
                : null;
        return new AiAlertPageResponse(sessionId, items, nextCursor);
    }

    private void requireTeacherReader(Long userId, Long sessionId) {
        try {
            auth.requireLearnerWithPermission(userId, "AI_ALERTS_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        // Session owner gate — generic denial, no existence leak (the
        // monitoring convention; a foreign teacher learns nothing).
        sessionRepo.findById(sessionId)
                .filter(s -> userId.equals(s.getCreatedBy()))
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }

    private static Object[] buildArgs(Long sessionId, String statusFilter, Long cursorId, int limit) {
        int n = 1 + (statusFilter != null ? 1 : 0) + (cursorId != null ? 1 : 0) + 1;
        Object[] args = new Object[n];
        int i = 0;
        args[i++] = sessionId;
        if (statusFilter != null) {
            args[i++] = statusFilter;
        }
        if (cursorId != null) {
            args[i++] = cursorId;
        }
        args[i] = limit;
        return args;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!MonitoringAiAlert.STATUS_OPEN.equals(value)
                && !MonitoringAiAlert.STATUS_CLOSED.equals(value)) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "status must be OPEN or CLOSED");
        }
        return value;
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

    private final RowMapper<Item> ALERT_ROW_MAPPER = (rs, i) -> new Item(
            rs.getLong("id"),
            rs.getLong("attempt_id"),
            rs.getLong("user_id"),
            rs.getString("student_name"),
            rs.getString("alert_type"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("first_detected_at")),
            toInstant(rs.getTimestamp("last_detected_at")),
            rs.getInt("detection_count"),
            rs.getObject("max_confidence") == null ? null : rs.getDouble("max_confidence"),
            rs.getString("model_id"),
            rs.getString("model_version"),
            rs.getString("provenance"),
            parsePayload(rs.getString("latest_payload")),
            parsePayload(rs.getString("first_payload")));

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private JsonNode parsePayload(String raw) {
        return raw == null ? null : objectMapper.readTree(raw);
    }
}
