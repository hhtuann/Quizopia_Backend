package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.exception.AttemptException;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import com.quizopia.backend.identity.repository.UserRepository;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.MonitoringAiAlert;
import com.quizopia.backend.monitoring.domain.model.MonitoringAlertReview;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewAttemptPageResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AttemptReviewRow;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewHistoryResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.CurrentReview;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.ReviewRow;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.MonitoringAiAlertRepository;
import com.quizopia.backend.monitoring.repository.MonitoringAlertReviewRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * P4-E teacher alert review (issue #96, ADR-010 §2.2).
 *
 * <p><b>Append-only ledger + denormalized current state.</b> Every review is
 * one {@code monitoring_alert_reviews} row; the alert's detector fields are
 * never rewritten — only {@code review_status}/{@code review_version}/
 * {@code reviewed_by}/{@code reviewed_at} move, in the SAME transaction.
 * The unique {@code (alert_id, review_version)} plus the caller-supplied
 * {@code expectedVersion} give optimistic concurrency; the unique
 * {@code idempotency_key} gives exactly-once appends for retried POSTs.
 *
 * <p>Append-only modulo the APPROVED retention window: rows older than the session activity_retention_days are swept by EvidenceRetentionJob (the denormalized alert state survives; version numbering derives from the alert, never the ledger). Reviews emit an {@code ALERT_REVIEW_RECORDED} activity event (same
 * transaction, via the MANDATORY-tx recorder) so they ride the unified
 * per-attempt timeline and the existing STOMP invalidation ping.
 *
 * <p>Never a verdict: no grade/attempt/session transitions exist here.
 */
@Service
public class AlertReviewService {

    private final AttemptAuthorizationService auth;
    private final ExamSessionRepository sessionRepo;
    private final MonitoringAiAlertRepository alertRepo;
    private final MonitoringAlertReviewRepository reviewRepo;
    private final UserRepository userRepo;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ActivityEventRecorder activity;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * F01: the ledger append (+ denorm update + timeline event) runs in its
     * own REQUIRES_NEW transaction; a lost unique-constraint race rolls it
     * back atomically and the idempotency probe below runs on the caller's
     * clean (transaction-less) connection.
     */
    private final TransactionTemplate appendTx;

    public AlertReviewService(AttemptAuthorizationService auth,
            ExamSessionRepository sessionRepo,
            MonitoringAiAlertRepository alertRepo,
            MonitoringAlertReviewRepository reviewRepo,
            UserRepository userRepo,
            ActivityEventRecorder activity,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.auth = auth;
        this.sessionRepo = sessionRepo;
        this.alertRepo = alertRepo;
        this.reviewRepo = reviewRepo;
        this.userRepo = userRepo;
        this.jdbc = jdbc;
        this.activity = activity;
        this.objectMapper = objectMapper;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.appendTx = template;
    }

    /**
     * Appends a review (or replays the stored one for a repeated
     * idempotency key). Conflict semantics:
     * <ul>
     *   <li>{@code expectedVersion != alert.reviewVersion} ⇒ 409 with the
     *       current version (optimistic concurrency).</li>
     *   <li>Same key, same alert ⇒ stored outcome, {@code idempotentReplay}
     *       = true — even after later reviews moved the version on.</li>
     *   <li>Same key, different alert ⇒ generic rejection (key reuse).</li>
     * </ul>
     */
    public AlertReviewResponse review(Long userId, Long alertId, int expectedVersion,
            String action, String note, UUID idempotencyKey) {
        requireReviewer(userId, alertId);

        String normalizedAction = normalizeAction(action);
        String normalizedNote = normalizeNote(note);

        // Idempotency probe BEFORE anything else — a retried POST returns
        // the stored outcome without re-validating versions.
        var existing = reviewRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            MonitoringAlertReview stored = existing.get();
            if (!stored.getAlertId().equals(alertId)) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_REVIEW_REJECTED,
                        "idempotencyKey already used by a different review");
            }
            return toResponse(stored, true);
        }

        MonitoringAiAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ALERT_NOT_FOUND));

        if (alert.getReviewVersion() != expectedVersion) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT,
                    "Alert review was updated by another reviewer (current version "
                            + alert.getReviewVersion() + "); reload and retry");
        }

        // F01: the append runs in its own REQUIRES_NEW transaction. When a
        // racing append wins a unique constraint, the loser's append
        // transaction rolls back COMPLETELY (no half-marked outer tx, no
        // UnexpectedRollbackException on commit) and this method — which has
        // NO transaction of its own — probes the winner on a clean
        // connection and returns the replay/conflict verdict.
        try {
            return appendTx.execute(status -> {
                MonitoringAiAlert locked = alertRepo.findById(alertId)
                        .orElseThrow(() -> new MonitoringException(
                                MonitoringErrorCode.MONITORING_ALERT_NOT_FOUND));
                // Strict optimistic concurrency on the TRANSACTION'S read:
                // the entry check may be stale (a racer committed between the
                // check and this tx) — a stale caller must CONFLICT, never
                // silently append a later version (CI five-way race caught
                // exactly one success turning into two).
                if (locked.getReviewVersion() != expectedVersion) {
                    throw new MonitoringException(
                            MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT,
                            "Alert review was updated by another reviewer (current version "
                                    + locked.getReviewVersion() + "); reload and retry");
                }
                MonitoringAlertReview appended = MonitoringAlertReview.append(locked, userId,
                        normalizedAction, normalizedNote, idempotencyKey);
                reviewRepo.saveAndFlush(appended);
                Instant reviewedAt = Instant.now(clock);
                locked.applyReview(appended.getReviewVersion(),
                        reviewStatusOf(normalizedAction), userId, reviewedAt);
                alertRepo.saveAndFlush(locked);
                recordReviewEvent(locked, appended);
                return new AlertReviewResponse(alertId, appended.getReviewVersion(),
                        reviewStatusOf(normalizedAction), normalizedAction, normalizedNote,
                        userId, reviewedAt, false);
            });
        } catch (DataIntegrityViolationException e) {
            // Concurrent same-key append (idempotency unique) or a lost
            // version race ((alert_id, review_version) unique).
            var winner = reviewRepo.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent() && winner.get().getAlertId().equals(alertId)) {
                return toResponse(winner.get(), true);
            }
            throw new MonitoringException(MonitoringErrorCode.MONITORING_REVIEW_VERSION_CONFLICT,
                    "Alert review was updated by another reviewer; reload and retry");
        }
    }

    /**
     * F11 drilldown: cursor-paged attempt-scoped review history (newest
     * first, opaque numeric cursor = last review id) for the report's
     * completeness path. Same read gate as the feed.
     */
    @Transactional(readOnly = true)
    public AlertReviewAttemptPageResponse attemptReviews(Long userId,
            Long attemptId, String cursor, int limit) {
        try {
            auth.requireLearnerWithPermission(userId, "AI_ALERTS_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        Long sessionId;
        try {
            sessionId = jdbc.queryForObject(
                    "SELECT exam_session_id FROM attempts WHERE id = ?", Long.class, attemptId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND);
        }
        sessionRepo.findById(sessionId)
                .filter(ses -> userId.equals(ses.getCreatedBy()))
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                cursorId = Long.parseLong(cursor.trim());
            } catch (NumberFormatException e) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_CURSOR_INVALID);
            }
        }
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.alert_id, r.action, r.note, u.display_name AS reviewer, "
                        + "r.created_at, r.review_version "
                        + "FROM monitoring_alert_reviews r "
                        + "JOIN users u ON u.id = r.reviewer_user_id "
                        + "WHERE r.alert_id IN "
                        + "(SELECT id FROM monitoring_ai_alerts WHERE attempt_id = ?) ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(attemptId);
        if (cursorId != null) {
            sql.append(" AND r.id < ?");
            args.add(cursorId);
        }
        sql.append(" ORDER BY r.id DESC LIMIT ?");
        args.add(pageSize + 1);
        java.util.List<AttemptReviewRow> rows = new java.util.ArrayList<>();
        jdbc.query(sql.toString(), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            rows.add(new AttemptReviewRow(
                    rs.getLong("id"),
                    (Long) rs.getObject("alert_id"),
                    rs.getInt("review_version"),
                    rs.getString("action"),
                    rs.getString("note"),
                    rs.getString("reviewer"),
                    rs.getTimestamp("created_at").toInstant()));
        }, args.toArray());
        boolean hasNext = rows.size() > pageSize;
        java.util.List<AttemptReviewRow> items =
                hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext && !items.isEmpty()
                ? String.valueOf(items.get(items.size() - 1).reviewId())
                : null;
        return new AlertReviewAttemptPageResponse(attemptId, items, nextCursor);
    }

    /** Append-only history + current denormalized state. */
    @Transactional(readOnly = true)
    public AlertReviewHistoryResponse history(Long userId, Long alertId) {
        MonitoringAiAlert alert = requireReader(userId, alertId);
        List<MonitoringAlertReview> rows =
                reviewRepo.findByAlertIdOrderByReviewVersionAsc(alertId);

        Map<Long, String> reviewerNames = reviewerNames(rows);

        CurrentReview current = alert.getReviewVersion() == 0
                ? new CurrentReview(MonitoringAiAlert.REVIEW_UNREVIEWED, 0, null, null)
                : new CurrentReview(alert.getReviewStatus(), alert.getReviewVersion(),
                        alert.getReviewedAt(),
                        displayNameOf(alert.getReviewedBy(), reviewerNames));
        List<ReviewRow> history = rows.stream()
                .map(r -> new ReviewRow(r.getId(), r.getReviewVersion(), r.getAction(),
                        r.getNote(), displayNameOf(r.getReviewerUserId(), reviewerNames),
                        r.getCreatedAt()))
                .toList();
        return new AlertReviewHistoryResponse(alertId, current, history);
    }

    // ------------------------------------------------------------------
    // Authorization
    // ------------------------------------------------------------------

    /** Write gate: MONITORING_ALERT_REVIEW + session ownership. */
    private void requireReviewer(Long userId, Long alertId) {
        try {
            auth.requireLearnerWithPermission(userId, "MONITORING_ALERT_REVIEW");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        MonitoringAiAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ALERT_NOT_FOUND));
        sessionRepo.findById(alert.getExamSessionId())
                .filter(s -> userId.equals(s.getCreatedBy()))
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ACCESS_DENIED));
    }

    /** Read gate: AI_ALERTS_READ + session ownership (feed convention). */
    private MonitoringAiAlert requireReader(Long userId, Long alertId) {
        try {
            auth.requireLearnerWithPermission(userId, "AI_ALERTS_READ");
        } catch (AttemptException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        MonitoringAiAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ALERT_NOT_FOUND));
        sessionRepo.findById(alert.getExamSessionId())
                .filter(s -> userId.equals(s.getCreatedBy()))
                .orElseThrow(() -> new MonitoringException(
                        MonitoringErrorCode.MONITORING_ACCESS_DENIED));
        return alert;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String normalizeAction(String action) {
        if (action == null || !MonitoringAlertReview.ACTIONS.contains(action.trim())) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_REVIEW_REJECTED,
                    "action must be one of MARK_VIEWED, FALSE_POSITIVE, "
                            + "NEEDS_FOLLOW_UP, NOTED, REVIEWED_NO_CONCLUSION");
        }
        return action.trim();
    }

    /** MARK_VIEWED denormalizes to VIEWED; the other actions map 1:1. */
    private static String reviewStatusOf(String action) {
        return MonitoringAlertReview.ACTION_MARK_VIEWED.equals(action) ? "VIEWED" : action;
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 1000) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_REVIEW_REJECTED,
                    "note must not exceed 1000 characters");
        }
        return trimmed;
    }

    private void recordReviewEvent(MonitoringAiAlert alert, MonitoringAlertReview appended) {
        // Drilldown refs only — note content never enters the timeline.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("alertId", alert.getId());
        metadata.put("reviewId", appended.getId());
        metadata.put("action", appended.getAction());
        metadata.put("reviewVersion", appended.getReviewVersion());
        activity.recordServerEvent(alert.getAttemptId(), alert.getExamSessionId(),
                alert.getUserId(), ActivityEventType.ALERT_REVIEW_RECORDED,
                appended.getCreatedAt(), objectMapper.valueToTree(metadata).toString());
    }

    private AlertReviewResponse toResponse(MonitoringAlertReview stored, boolean replay) {
        return new AlertReviewResponse(stored.getAlertId(), stored.getReviewVersion(),
                reviewStatusOf(stored.getAction()), stored.getAction(), stored.getNote(),
                stored.getReviewerUserId(), stored.getCreatedAt(), replay);
    }

    private Map<Long, String> reviewerNames(List<MonitoringAlertReview> rows) {
        Map<Long, String> names = new HashMap<>();
        for (MonitoringAlertReview row : rows) {
            names.computeIfAbsent(row.getReviewerUserId(),
                    id -> userRepo.findById(id).map(u -> u.getDisplayName()).orElse(null));
        }
        return names;
    }

    private String displayNameOf(Long userId, Map<Long, String> cached) {
        if (userId == null) {
            return null;
        }
        return cached.computeIfAbsent(userId,
                id -> userRepo.findById(id).map(u -> u.getDisplayName()).orElse(null));
    }
}
