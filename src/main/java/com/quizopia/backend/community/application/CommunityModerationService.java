package com.quizopia.backend.community.application;

import com.quizopia.backend.community.domain.model.CommunityComment;
import com.quizopia.backend.community.domain.model.CommunityCommentReport;
import com.quizopia.backend.community.domain.model.CommunityCommentState;
import com.quizopia.backend.community.domain.model.CommunityModerationAction;
import com.quizopia.backend.community.domain.model.CommunityModerationActionType;
import com.quizopia.backend.community.domain.model.CommunityModerationState;
import com.quizopia.backend.community.domain.model.CommunityPublication;
import com.quizopia.backend.community.domain.model.CommunityReport;
import com.quizopia.backend.community.domain.model.CommunityReportStatus;
import com.quizopia.backend.community.dto.ModerationCommentEntry;
import com.quizopia.backend.community.dto.ModerationCommentHistoryEntry;
import com.quizopia.backend.community.dto.ModerationCommentReportEntry;
import com.quizopia.backend.community.dto.ModerationCommentSummary;
import com.quizopia.backend.community.dto.ModerationHistoryEntry;
import com.quizopia.backend.community.dto.ModerationPublicationSummary;
import com.quizopia.backend.community.dto.ModerationReportEntry;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityCategoryRepository;
import com.quizopia.backend.community.repository.CommunityCommentReportRepository;
import com.quizopia.backend.community.repository.CommunityCommentRepository;
import com.quizopia.backend.community.repository.CommunityModerationActionRepository;
import com.quizopia.backend.community.repository.CommunityPublicationRepository;
import com.quizopia.backend.community.repository.CommunityPublicationTagRepository;
import com.quizopia.backend.community.repository.CommunityReportRepository;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Moderation application service (ADR-006 §4/§5/§7, issue #42): report
 * queue, hide/restore, report dismiss/resolve, per-publication history.
 *
 * <p>Authorization on every entry point: the caller must be ACTIVE and
 * hold the effective {@code COMMUNITY_MODERATION} permission
 * ({@link CommunityAuthorizationService#requireActiveUserWithPermission})
 * — no role literal, no admin bypass; the permission resolves live from
 * the DB per request, so an operator revocation takes effect on the
 * very next call. Nothing here can grant or revoke the moderator grant
 * itself (operator runbook only, §5).
 *
 * <p>Lock discipline (§4/§8): hide/restore take ONLY the publication
 * row lock ({@code findByIdForUpdate}) — never the exam lock;
 * dismiss/resolve take ONLY the report row lock. Idempotent repeats
 * (hide-while-HIDDEN, restore-while-VISIBLE) are 200 no-ops WITHOUT a
 * duplicate audit row. Restore never touches {@code status}. The #66
 * comment twins (ADR-007 §5) mirror all of it publication-lock-free:
 * comment hide/restore take ONLY the comment row lock, comment-report
 * dismiss/resolve ONLY the comment-report row lock, repeats are 200
 * no-ops without a duplicate audit row, and DELETED is a 409
 * COMMUNITY_COMMENT_STATE_INVALID for both hide and restore — an
 * owner tombstone can never be moderated back.
 *
 * <p>Rate admission (§9): every write — hide, restore, dismiss,
 * resolve — admits FIRST against the caller's MODERATION user bucket
 * (60/h) before any authorization read or lock, so a compromised
 * moderator cannot flood {@code community_moderation_actions}.
 */
@Service
public class CommunityModerationService {

    static final String PERMISSION_MODERATION = "COMMUNITY_MODERATION";
    private static final int MAX_PAGE_SIZE = 50;

    private final CommunityPublicationRepository publications;
    private final CommunityReportRepository reports;
    private final CommunityModerationActionRepository actions;
    private final CommunityPublicationTagRepository publicationTags;
    private final CommunityCategoryRepository categories;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityCommentRepository comments;
    private final CommunityCommentReportRepository commentReports;
    private final CommunityCommentAuthorStore authorStore;
    private final Clock clock;

    public CommunityModerationService(CommunityPublicationRepository publications,
            CommunityReportRepository reports,
            CommunityModerationActionRepository actions,
            CommunityPublicationTagRepository publicationTags,
            CommunityCategoryRepository categories,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            CommunityCommentRepository comments,
            CommunityCommentReportRepository commentReports,
            CommunityCommentAuthorStore authorStore,
            Clock clock) {
        this.publications = publications;
        this.reports = reports;
        this.actions = actions;
        this.publicationTags = publicationTags;
        this.categories = categories;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.comments = comments;
        this.commentReports = commentReports;
        this.authorStore = authorStore;
        this.clock = clock;
    }

    // ============================================================
    // GET /api/community/moderation/grant
    // ============================================================

    /** FE console gate: 200 only for an ACTIVE effective moderator. */
    public boolean hasModerationGrant(Long userId) {
        try {
            auth.requireActiveUserWithPermission(userId, PERMISSION_MODERATION);
            return true;
        } catch (CommunityException ex) {
            if (ex.getErrorCode() != CommunityErrorCode.COMMUNITY_ACCESS_DENIED) {
                throw ex;
            }
            return false;
        }
    }

    // ============================================================
    // GET /api/community/moderation/reports?status&page&size
    // ============================================================

    @Transactional(readOnly = true)
    public PageResponse<ModerationReportEntry> reportQueue(
            Long userId, String status, int page, int size) {
        requireModerator(userId);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        // Deterministic queue order: created_at DESC, id DESC (§11).
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<CommunityReport> result = status == null || status.isBlank()
                ? reports.findAll(pageable)
                : reports.findByStatus(parseStatus(status), pageable);

        Map<Long, ModerationPublicationSummary> summaries =
                publicationSummaries(result.getContent().stream()
                        .map(CommunityReport::getPublicationId).distinct().toList());
        return new PageResponse<>(
                result.getContent().stream()
                        .map(report -> toQueueEntry(report, summaries)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                pageable.getSort().toString());
    }

    // ============================================================
    // POST /api/community/moderation/publications/{id}/hide|restore
    // ============================================================

    @Transactional
    public ModerationPublicationSummary hide(Long userId, Long publicationId, String reason) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        requireModerator(userId);
        CommunityPublication publication = lockPublication(publicationId);
        String auditReason = requireReason(reason);

        if (publication.getModerationState() == CommunityModerationState.HIDDEN) {
            // Idempotent no-op (§4): 200, NO duplicate audit row.
            return summary(publication);
        }

        String status = publication.getStatus().name();
        publication.hideByModeration();
        publications.saveAndFlush(publication);
        appendAudit(userId, publicationId, null, CommunityModerationActionType.HIDE,
                status, status,
                CommunityModerationState.VISIBLE.name(), CommunityModerationState.HIDDEN.name(),
                auditReason);
        return summary(publication);
    }

    @Transactional
    public ModerationPublicationSummary restore(Long userId, Long publicationId, String reason) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        requireModerator(userId);
        CommunityPublication publication = lockPublication(publicationId);
        String auditReason = requireReason(reason);

        if (publication.getModerationState() == CommunityModerationState.VISIBLE) {
            // Idempotent no-op (§4): 200, NO duplicate audit row.
            return summary(publication);
        }

        String status = publication.getStatus().name();
        publication.restoreByModeration();
        publications.saveAndFlush(publication);
        // Restore does NOT change `status` (§4): from_status == to_status
        // records the (possibly UNPUBLISHED) lifecycle state unchanged.
        appendAudit(userId, publicationId, null, CommunityModerationActionType.RESTORE,
                status, status,
                CommunityModerationState.HIDDEN.name(), CommunityModerationState.VISIBLE.name(),
                auditReason);
        return summary(publication);
    }

    // ============================================================
    // POST /api/community/moderation/reports/{id}/dismiss|resolve
    // ============================================================

    @Transactional
    public ModerationReportEntry dismissReport(Long userId, Long reportId, String note) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        return handleReport(userId, reportId, note, CommunityModerationActionType.REPORT_DISMISS);
    }

    @Transactional
    public ModerationReportEntry resolveReport(Long userId, Long reportId, String note) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        return handleReport(userId, reportId, note, CommunityModerationActionType.REPORT_RESOLVE);
    }

    private ModerationReportEntry handleReport(Long userId, Long reportId, String note,
            CommunityModerationActionType action) {
        requireModerator(userId);
        CommunityReport report = reports.findForUpdateById(reportId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        if (report.getStatus() != CommunityReportStatus.OPEN) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_REPORT_NOT_OPEN);
        }

        String trimmedNote = note == null ? null : note.trim();
        if (trimmedNote != null && trimmedNote.isEmpty()) {
            trimmedNote = null;
        }
        if (trimmedNote != null && trimmedNote.length() > 500) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "note must be at most 500 characters");
        }

        Instant now = Instant.now(clock);
        if (action == CommunityModerationActionType.REPORT_DISMISS) {
            report.dismiss(userId, now, trimmedNote);
        } else {
            report.resolve(userId, now, trimmedNote);
        }
        reports.saveAndFlush(report);

        // Audit reason is NOT NULL (V27): the note when given, else the
        // action verb — always ≥1 char (§7 dismiss/resolve take {note?}).
        String auditReason = trimmedNote != null ? trimmedNote : action.name();
        appendAudit(userId, report.getPublicationId(), report.getId(), action,
                null, null, null, null, auditReason);

        ModerationPublicationSummary summary = summary(
                publications.findById(report.getPublicationId()).orElse(null));
        return toQueueEntry(report, Map.of(report.getPublicationId(), summary));
    }

    // ============================================================
    // GET /api/community/moderation/publications/{id}/history
    // ============================================================

    @Transactional(readOnly = true)
    public List<ModerationHistoryEntry> publicationHistory(Long userId, Long publicationId) {
        requireModerator(userId);
        if (!publications.existsById(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
        // FE #44 contract: UNPAGED array of audit rows, newest-first.
        return actions
                .findByPublicationIdOrderByCreatedAtDescIdDesc(publicationId, Pageable.unpaged())
                .stream().map(CommunityModerationService::toHistoryEntry).toList();
    }

    // ============================================================
    // #66 (ADR-007 §5) — comment moderation
    // ============================================================

    /**
     * GET /api/community/moderation/comment-reports?status&page&size —
     * the comment-reports queue, newest-first, optional status filter.
     * Batched comment + publication lookups (no N+1).
     */
    @Transactional(readOnly = true)
    public PageResponse<ModerationCommentReportEntry> commentReportQueue(
            Long userId, String status, int page, int size) {
        requireModerator(userId);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<CommunityCommentReport> result = status == null || status.isBlank()
                ? commentReports.findAll(pageable)
                : commentReports.findByStatus(parseStatus(status), pageable);

        List<Long> commentIds = result.getContent().stream()
                .map(CommunityCommentReport::getCommentId).distinct().toList();
        Map<Long, CommunityComment> commentById = commentIds.isEmpty()
                ? Map.of()
                : comments.findAllById(commentIds).stream()
                        .collect(Collectors.toMap(CommunityComment::getId, Function.identity()));
        Map<Long, ModerationPublicationSummary> summaries = publicationSummaries(
                commentById.values().stream().map(CommunityComment::getPublicationId)
                        .distinct().toList());
        Map<Long, String> authorNames = authorStore.displayNamesOf(
                commentById.values().stream().map(CommunityComment::getAuthorUserId)
                        .distinct().toList());

        return new PageResponse<>(
                result.getContent().stream()
                        .map(report -> toCommentQueueEntry(report, commentById, authorNames,
                                summaries))
                        .toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), pageable.getSort().toString());
    }

    /**
     * POST /moderation/comments/{commentId}/hide — VISIBLE→HIDDEN under
     * the comment row lock ONLY (never the publication row); audit
     * COMMENT_HIDE carries the state pair in from/to_moderation_state
     * and NULL statuses. Repeat hide is a 200 no-op without a duplicate
     * audit row; hide of DELETED is 409 (tombstones are terminal).
     */
    @Transactional
    public ModerationCommentEntry hideComment(Long userId, Long commentId, String reason) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        requireModerator(userId);
        CommunityComment comment = lockComment(commentId);
        String auditReason = requireReason(reason);

        if (comment.getState() == CommunityCommentState.DELETED) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID);
        }
        if (comment.getState() == CommunityCommentState.HIDDEN) {
            // Idempotent no-op: 200, NO duplicate audit row.
            return toCommentEntry(comment);
        }

        comment.hideByModeration();
        comments.saveAndFlush(comment);
        appendCommentAudit(userId, commentId, CommunityModerationActionType.COMMENT_HIDE,
                CommunityCommentState.VISIBLE.name(), CommunityCommentState.HIDDEN.name(),
                auditReason);
        return toCommentEntry(comment);
    }

    /**
     * POST /moderation/comments/{commentId}/restore — HIDDEN→VISIBLE
     * under the comment row lock ONLY; audit COMMENT_RESTORE. Repeat
     * restore is a 200 no-op without a duplicate audit row; restore of
     * DELETED is 409 (tombstones are terminal).
     */
    @Transactional
    public ModerationCommentEntry restoreComment(Long userId, Long commentId, String reason) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        requireModerator(userId);
        CommunityComment comment = lockComment(commentId);
        String auditReason = requireReason(reason);

        if (comment.getState() == CommunityCommentState.DELETED) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID);
        }
        if (comment.getState() == CommunityCommentState.VISIBLE) {
            // Idempotent no-op: 200, NO duplicate audit row.
            return toCommentEntry(comment);
        }

        comment.restoreByModeration();
        comments.saveAndFlush(comment);
        appendCommentAudit(userId, commentId, CommunityModerationActionType.COMMENT_RESTORE,
                CommunityCommentState.HIDDEN.name(), CommunityCommentState.VISIBLE.name(),
                auditReason);
        return toCommentEntry(comment);
    }

    /**
     * POST /moderation/comment-reports/{id}/dismiss|resolve — the #42
     * transitions on the comment-report row lock ONLY; audit rows link
     * {@code comment_report_id} and carry no states; 409
     * COMMUNITY_REPORT_NOT_OPEN when not OPEN.
     */
    @Transactional
    public ModerationCommentReportEntry dismissCommentReport(Long userId, Long reportId,
            String note) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        return handleCommentReport(userId, reportId, note,
                CommunityModerationActionType.COMMENT_REPORT_DISMISS);
    }

    @Transactional
    public ModerationCommentReportEntry resolveCommentReport(Long userId, Long reportId,
            String note) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.MODERATION, userId);
        return handleCommentReport(userId, reportId, note,
                CommunityModerationActionType.COMMENT_REPORT_RESOLVE);
    }

    private ModerationCommentReportEntry handleCommentReport(Long userId, Long reportId,
            String note, CommunityModerationActionType action) {
        requireModerator(userId);
        CommunityCommentReport report = commentReports.findForUpdateById(reportId)
                .orElseThrow(() -> new CommunityException(
                        CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        if (report.getStatus() != CommunityReportStatus.OPEN) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_REPORT_NOT_OPEN);
        }

        String trimmedNote = boundNote(note);
        Instant now = Instant.now(clock);
        if (action == CommunityModerationActionType.COMMENT_REPORT_DISMISS) {
            report.dismiss(userId, now, trimmedNote);
        } else {
            report.resolve(userId, now, trimmedNote);
        }
        commentReports.saveAndFlush(report);

        // V27 reason NOT NULL: the note when given, else the action verb.
        appendCommentReportAudit(userId, report.getId(), action,
                trimmedNote != null ? trimmedNote : action.name());

        return refreshedCommentQueueEntry(report);
    }

    /**
     * GET /moderation/comments/{commentId}/history — audit rows
     * newest-first, UNPAGED (publication-history precedent).
     */
    @Transactional(readOnly = true)
    public List<ModerationCommentHistoryEntry> commentHistory(Long userId, Long commentId) {
        requireModerator(userId);
        if (!comments.existsById(commentId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        return actions.findByCommentIdOrderByCreatedAtDescIdDesc(commentId).stream()
                .map(CommunityModerationService::toCommentHistoryEntry).toList();
    }

    // ============================================================
    // helpers
    // ============================================================

    private void requireModerator(Long userId) {
        auth.requireActiveUserWithPermission(userId, PERMISSION_MODERATION);
    }

    /** Publication row lock ONLY (§4/§8) — never the exam lock. */
    private CommunityPublication lockPublication(Long publicationId) {
        return publications.findByIdForUpdate(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
    }

    /** Mandatory hide/restore reason: 1..500 after trim (§7). */
    private static String requireReason(String reason) {
        if (reason == null) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reason must not be blank");
        }
        if (trimmed.length() > 500) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reason must be at most 500 characters");
        }
        return trimmed;
    }

    private static CommunityReportStatus parseStatus(String raw) {
        try {
            return CommunityReportStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "status must be one of OPEN, DISMISSED, RESOLVED");
        }
    }

    private void appendAudit(Long actorUserId, Long publicationId, Long reportId,
            CommunityModerationActionType action, String fromStatus, String toStatus,
            String fromModerationState, String toModerationState, String reason) {
        actions.save(new CommunityModerationAction(actorUserId, publicationId, reportId,
                action, fromStatus, toStatus, fromModerationState, toModerationState,
                reason, null));
    }

    /** Batched queue summaries (publications + categories + tags). */
    private Map<Long, ModerationPublicationSummary> publicationSummaries(List<Long> publicationIds) {
        if (publicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CommunityPublication> byId = publications.findAllById(publicationIds).stream()
                .collect(Collectors.toMap(CommunityPublication::getId, Function.identity()));
        return byId.values().stream()
                .collect(Collectors.toMap(CommunityPublication::getId,
                        publication -> summary(publication)));
    }

    private ModerationPublicationSummary summary(CommunityPublication publication) {
        if (publication == null) {
            return null;
        }
        String categoryCode = null;
        String categoryTitle = null;
        if (publication.getCategoryId() != null) {
            var category = categories.findById(publication.getCategoryId()).orElse(null);
            if (category != null) {
                categoryCode = category.getCode();
                categoryTitle = category.getTitle();
            }
        }
        List<String> tagLabels = publicationTags
                .findLabelsByPublicationIds(List.of(publication.getId())).stream()
                .map(row -> (String) row[1]).toList();
        return new ModerationPublicationSummary(
                publication.getId(),
                publication.getTitle(),
                publication.getSummary(),
                publication.getAuthorAlias(), // FE card field name: authorName
                publication.getCategoryId(),
                categoryCode,
                categoryTitle,
                tagLabels,
                publication.getStatus().name(),
                publication.getModerationState().name(),
                publication.getReusePolicy().name(),
                publication.getQuestionCount(),
                publication.getSectionCount(),
                publication.getDurationMinutes(),
                publication.getPublishedAt(),
                publication.getCreatedAt(),
                publication.getUpdatedAt());
    }

    private static ModerationReportEntry toQueueEntry(CommunityReport report,
            Map<Long, ModerationPublicationSummary> summaries) {
        return new ModerationReportEntry(
                report.getId(),
                report.getPublicationId(),
                summaries.get(report.getPublicationId()),
                report.getReporterUserId(),
                report.getReasonCode().name(),
                report.getDetails(),
                report.getStatus().name(),
                report.getHandledBy(),
                report.getHandledAt(),
                report.getResolutionNote(),
                report.getCreatedAt());
    }

    private static ModerationHistoryEntry toHistoryEntry(CommunityModerationAction action) {
        return new ModerationHistoryEntry(
                action.getId(),
                action.getActorUserId(),
                action.getAction().name(),
                action.getReportId(),
                action.getFromStatus(),
                action.getToStatus(),
                action.getFromModerationState(),
                action.getToModerationState(),
                action.getReason(),
                action.getCreatedAt());
    }

    /** Comment row lock ONLY (ADR-007 §5/§8) — never the publication row. */
    private CommunityComment lockComment(Long commentId) {
        return comments.findForUpdateById(commentId)
                .orElseThrow(() -> new CommunityException(
                        CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    /** §7 dismiss/resolve optional note: trimmed, ≤500, blank → null. */
    private static String boundNote(String note) {
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > 500) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "note must be at most 500 characters");
        }
        return trimmed;
    }

    private void appendCommentAudit(Long actorUserId, Long commentId,
            CommunityModerationActionType action, String fromState, String toState,
            String reason) {
        actions.save(new CommunityModerationAction(actorUserId, action, commentId, null,
                fromState, toState, reason));
    }

    private void appendCommentReportAudit(Long actorUserId, Long commentReportId,
            CommunityModerationActionType action, String reason) {
        actions.save(new CommunityModerationAction(actorUserId, action, null, commentReportId,
                null, null, reason));
    }

    /** Queue entry for a just-handled report (fresh comment + summary reads). */
    private ModerationCommentReportEntry refreshedCommentQueueEntry(CommunityCommentReport report) {
        CommunityComment comment = comments.findById(report.getCommentId()).orElse(null);
        Map<Long, CommunityComment> commentById = comment == null
                ? Map.of()
                : Map.of(comment.getId(), comment);
        Map<Long, String> authorNames = comment == null
                ? Map.of()
                : authorStore.displayNamesOf(List.of(comment.getAuthorUserId()));
        return toCommentQueueEntry(report, commentById, authorNames,
                publicationSummaries(comment == null
                        ? List.of()
                        : List.of(comment.getPublicationId())));
    }

    private static ModerationCommentReportEntry toCommentQueueEntry(
            CommunityCommentReport report, Map<Long, CommunityComment> commentById,
            Map<Long, String> authorNames,
            Map<Long, ModerationPublicationSummary> summaries) {
        CommunityComment comment = commentById.get(report.getCommentId());
        return new ModerationCommentReportEntry(
                report.getId(),
                report.getCommentId(),
                comment == null ? null : new ModerationCommentSummary(
                        comment.getBody(),
                        authorNames.getOrDefault(comment.getAuthorUserId(),
                                CommunityCommentAuthorStore.FALLBACK_AUTHOR_NAME),
                        comment.getCreatedAt()),
                comment == null ? null : summaries.get(comment.getPublicationId()),
                report.getReporterUserId(),
                report.getReasonCode().name(),
                report.getDetails(),
                report.getStatus().name(),
                report.getHandledBy(),
                report.getHandledAt(),
                report.getResolutionNote(),
                report.getCreatedAt());
    }

    /** {@code authorName} = the author's CURRENT display name (Rev 4 / #72). */
    private ModerationCommentEntry toCommentEntry(CommunityComment comment) {
        return new ModerationCommentEntry(
                comment.getId(),
                comment.getPublicationId(),
                authorStore.displayNameOf(comment.getAuthorUserId()),
                comment.getBody(),
                comment.getState().name(),
                comment.getCreatedAt(),
                comment.getEditedAt(),
                comment.getUpdatedAt());
    }

    private static ModerationCommentHistoryEntry toCommentHistoryEntry(
            CommunityModerationAction action) {
        return new ModerationCommentHistoryEntry(
                action.getId(),
                action.getActorUserId(),
                action.getAction().name(),
                action.getCommentId(),
                action.getCommentReportId(),
                action.getFromModerationState(),
                action.getToModerationState(),
                action.getReason(),
                action.getCreatedAt());
    }
}
