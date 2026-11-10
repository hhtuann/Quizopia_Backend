package com.quizopia.backend.community.application;

import com.quizopia.backend.community.application.CommunityCommentAuthorStore.VisibleCommentRow;
import com.quizopia.backend.community.domain.model.CommunityComment;
import com.quizopia.backend.community.domain.model.CommunityCommentReport;
import com.quizopia.backend.community.domain.model.CommunityCommentState;
import com.quizopia.backend.community.domain.model.CommunityReportReason;
import com.quizopia.backend.community.dto.CommentResponse;
import com.quizopia.backend.community.dto.CommunityCommentReportResponse;
import com.quizopia.backend.community.dto.CreateCommentRequest;
import com.quizopia.backend.community.dto.PublicCommentEntry;
import com.quizopia.backend.community.dto.ReportCommentRequest;
import com.quizopia.backend.community.dto.UpdateCommentRequest;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityCommentReportRepository;
import com.quizopia.backend.community.repository.CommunityCommentRepository;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Comment application service (ADR-007 §5/§6, issue #66; ADR-007 Rev 4
 * / #72): create, public page, owner PATCH/DELETE and comment reports.
 * Every write runs rate admission then the permission gate inside the
 * write transaction, but semantic validation is placed per route, not
 * in one fixed module-wide order: {@code reportComment} validates the
 * bounded reason/details shape FIRST — garbage never admits, the
 * rating-PUT precedent — before the COMMENT_REPORT 5/h user + 20/h IP
 * admission pair and the ACTIVE + COMMUNITY_REPORT gate;
 * {@code createComment} admits (COMMENT_CREATE 10/h), gates (ACTIVE +
 * effective COMMUNITY_COMMENT), runs the §4 visibility predicate →
 * anti-enumeration 404 and only THEN validates the body, before the
 * insert; {@code updateComment} admits (COMMENT_EDIT 20/h) and gates
 * before validating the body and firing the §6 CAS, while
 * {@code deleteComment} (COMMENT_DELETE 30/h) has no body at all. The
 * comment buckets stay separate from the publication REPORT_* policies
 * — the policy segment in the HMAC key exists for exactly this.
 *
 * <p><b>Author identity (Rev 4, #72)</b>: the comment author is the
 * authenticated user, full stop. The request carries the body only; a
 * legacy {@code alias} field is ignored by deserialization and can
 * never override the displayed name. The PUBLIC author name is the
 * author's CURRENT {@code users.display_name}, resolved server-side at
 * read time by {@link CommunityCommentAuthorStore} (duplicates
 * allowed; profile renames are reflected immediately; a privacy-safe
 * generic fallback replaces a missing name). The V31 alias consent
 * lifecycle (member profiles, alias validation, alias-taken 409) is
 * retired — see V32 and ADR-007 Rev 4.
 *
 * <p>Owner PATCH/DELETE check existence + ownership ONLY (§5 — users
 * keep control of their own UGC even on hidden publications; a hidden
 * publication never lists the comment publicly, but its author can
 * still edit/delete it). All 404s on a route are byte-identical
 * (COMMUNITY_COMMENT_NOT_FOUND for absent/foreign/non-VISIBLE).
 * PATCH is the state-guarded CAS
 * {@code UPDATE … WHERE author_user_id = ? AND state = 'VISIBLE'} —
 * rowcount 0 on an owned row is the 409 state conflict (a delete can
 * never be resurrected); DELETE is the idempotent tombstone over any
 * state, clearing the body and preserving {@code edited_at}.
 *
 * <p>The public page is VISIBLE-only, newest-first
 * ({@code created_at DESC, id DESC}), size 1..50 default 20, served by
 * the two-statement {@link CommunityCommentAuthorStore} projection
 * (count + JOIN page — no per-comment user lookup, no N+1). Item
 * ownership is the server-computed {@code isMine} flag: the OPTIONAL
 * authenticated caller id (present when a valid JWT accompanies the
 * otherwise anonymous public GET) compared against
 * {@code author_user_id} — never name-text equality, and anonymous
 * callers see {@code false} on every item.
 */
@Service
public class CommunityCommentService {

    static final String PERMISSION_COMMENT = "COMMUNITY_COMMENT";
    static final String PERMISSION_REPORT = "COMMUNITY_REPORT";

    /** ADR-007 §3: body VARCHAR(2000), trimmed non-blank printable text. */
    static final int BODY_MAX_LENGTH = 2000;
    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final CommunityCommentRepository comments;
    private final CommunityCommentReportRepository commentReports;
    private final CommunityCommentAuthorStore authorStore;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityVisibilityStore visibilityStore;
    private final Clock clock;

    public CommunityCommentService(CommunityCommentRepository comments,
            CommunityCommentReportRepository commentReports,
            CommunityCommentAuthorStore authorStore,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            CommunityVisibilityStore visibilityStore,
            Clock clock) {
        this.comments = comments;
        this.commentReports = commentReports;
        this.authorStore = authorStore;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.visibilityStore = visibilityStore;
        this.clock = clock;
    }

    /** Report submission outcome: the report body plus the 201-vs-200 flag. */
    public record SubmittedCommentReport(CommunityCommentReportResponse report, boolean created) {
    }

    // ============================================================
    // POST /api/community/publications/{id}/comments
    // ============================================================

    @Transactional
    public CommentResponse createComment(Long userId, Long publicationId,
            CreateCommentRequest request) {
        // (1) §5 admission — first statement of the write transaction.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.COMMENT_CREATE, userId);

        // (2) Permission gate: ACTIVE + effective COMMUNITY_COMMENT.
        auth.requireActiveUserWithPermission(userId, PERMISSION_COMMENT);

        // (3) §4 visibility predicate → 404 (never an existence oracle).
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }

        // (4) Body bounds — after admission/permission/visibility.
        String body = validateBody(request.body());

        // (5) Insert — author identity is the authenticated user; no
        // client-supplied name, no alias snapshot (V32: NULL).
        CommunityComment comment = comments.save(new CommunityComment(publicationId, userId, body));
        return toResponse(comment, authorStore.displayNameOf(userId));
    }

    // ============================================================
    // GET /api/public/community/publications/{id}/comments
    // ============================================================

    /**
     * The fifth public GET (§5): all VISIBLE comments of one §4-visible
     * publication, newest-first. Admission (PUBLIC_READ_IP posture) is
     * the caller's concern ({@code PublicCommunityService}); this
     * method only enforces the predicate and pages. No tombstone
     * placeholders (§3 deviation) — {@code totalElements} counts
     * VISIBLE only, the same predicate as the summary's commentCount.
     *
     * <p>{@code callerUserId} is the OPTIONAL authenticated caller
     * (null when anonymous): it ONLY computes the per-item
     * {@code isMine} flag — true exactly when the caller authored the
     * comment — and never changes filtering or shape. Anonymous calls
     * get {@code isMine:false} on every item.
     */
    public PageResponse<PublicCommentEntry> publicPage(
            long publicationId, int page, int size, Long callerUserId) {
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        CommunityCommentAuthorStore.VisibleCommentPage result =
                authorStore.visiblePage(publicationId, safePage, safeSize);
        List<PublicCommentEntry> items = result.rows().stream()
                .map(row -> toPublicEntry(row, callerUserId))
                .toList();
        int totalPages = (int) ((result.totalElements() + safeSize - 1) / safeSize);
        return new PageResponse<>(items, safePage, safeSize,
                result.totalElements(), totalPages, "createdAt DESC, id DESC");
    }

    // ============================================================
    // PATCH /api/community/comments/{commentId} (owner)
    // ============================================================

    /**
     * The §6 owner edit: single state-guarded CAS. Rowcount 1 → 200
     * with {@code edited_at} stamped; 0 on an owned row → 409
     * COMMUNITY_COMMENT_STATE_INVALID (HIDDEN or DELETED — a cleared
     * tombstone can never be resurrected); 0 unowned → the
     * byte-identical comment 404.
     */
    @Transactional
    public CommentResponse updateComment(Long userId, Long commentId,
            UpdateCommentRequest request) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.COMMENT_EDIT, userId);
        auth.requireActiveUserWithPermission(userId, PERMISSION_COMMENT);

        String body = validateBody(request.body());

        int updated = comments.updateBodyIfVisible(commentId, userId, body);
        if (updated == 0) {
            if (comments.existsByIdAndAuthorUserId(commentId, userId)) {
                throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_STATE_INVALID);
            }
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        CommunityComment comment = comments.findById(commentId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        return toResponse(comment, authorStore.displayNameOf(userId));
    }

    // ============================================================
    // DELETE /api/community/comments/{commentId} (owner)
    // ============================================================

    /**
     * The §6 owner tombstone: soft-delete over ANY state (idempotent —
     * a re-DELETE matches the same row), body cleared, edited_at
     * preserved. Rowcount 0 → foreign/absent → the byte-identical 404.
     */
    @Transactional
    public void deleteComment(Long userId, Long commentId) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.COMMENT_DELETE, userId);
        auth.requireActiveUserWithPermission(userId, PERMISSION_COMMENT);

        if (comments.deleteTombstoneByOwner(commentId, userId) == 0) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
    }

    // ============================================================
    // POST /api/community/comments/{commentId}/reports
    // ============================================================

    /**
     * §5 fixed order: admission (user bucket, then IP bucket — both
     * COMMENT_REPORT_* policies, independent of the publication REPORT
     * budgets) → COMMUNITY_REPORT permission → comment VISIBLE AND
     * parent §4-visible (byte-identical 404, never a state oracle) →
     * {@code ON CONFLICT … WHERE status='OPEN' DO NOTHING} dedupe (200
     * with the same report) → 201.
     */
    @Transactional
    public SubmittedCommentReport reportComment(Long userId, Long commentId,
            ReportCommentRequest request, String remoteAddr) {
        // (0) Semantic validation — bounded enum; garbage never admits.
        CommunityReportReason reason = CommunityReportReason.parse(request.reasonCode());
        if (reason == null) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "reasonCode must be one of INAPPROPRIATE_CONTENT, COPYRIGHT, MISLEADING, SPAM, OTHER");
        }
        String details = request.details() == null ? null : request.details().trim();
        if (details != null && details.isEmpty()) {
            details = null;
        }
        if (details != null && details.length() > 500) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "details must be at most 500 characters");
        }

        // (1) Rate admission — user bucket first, then IP bucket.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.COMMENT_REPORT_USER, userId);
        rateLimiter.admitIp(CommunityRateLimiter.Policy.COMMENT_REPORT_IP, remoteAddr);

        // (2) Gate: any ACTIVE user + effective COMMUNITY_REPORT.
        auth.requireActiveUserWithPermission(userId, PERMISSION_REPORT);

        // (3) Comment VISIBLE + parent §4 predicate → byte-identical 404.
        CommunityComment comment = comments.findById(commentId).orElse(null);
        if (comment == null || comment.getState() != CommunityCommentState.VISIBLE
                || !visibilityStore.isVisible(comment.getPublicationId())) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }

        // (4) Dedupe insert + re-read (#42 pattern): 1 affected row →
        // 201; 0 → an OPEN report already existed → 200 with it. A
        // concurrent dismissal can empty the re-read between the two
        // statements — retry the pair exactly once; still empty means
        // no OPEN report can exist for this caller: 404, never a 500.
        int inserted = commentReports.insertIfNoOpenCommentReport(
                commentId, userId, reason.name(), details, Instant.now(clock));
        CommunityCommentReport report =
                commentReports.findOpenByReporterAndComment(userId, commentId).orElse(null);
        if (report == null) {
            inserted = commentReports.insertIfNoOpenCommentReport(
                    commentId, userId, reason.name(), details, Instant.now(clock));
            report = commentReports.findOpenByReporterAndComment(userId, commentId)
                    .orElseThrow(() -> new CommunityException(
                            CommunityErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        }
        return new SubmittedCommentReport(toResponse(report), inserted == 1);
    }

    // ============================================================
    // internals
    // ============================================================

    /**
     * §3 body bounds: trimmed, non-blank, ≤2000 chars, printable text —
     * control characters other than {@code \n}/{@code \t} rejected at
     * this API layer; the stored value is verbatim (rendered escaped).
     */
    static String validateBody(String raw) {
        if (raw == null) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "body is required");
        }
        String body = raw.trim();
        if (body.isEmpty()) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "body must not be blank");
        }
        if (body.length() > BODY_MAX_LENGTH) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "body must be at most " + BODY_MAX_LENGTH + " characters");
        }
        body.codePoints().forEach(CommunityCommentService::requirePrintableCodePoint);
        return body;
    }

    private static void requirePrintableCodePoint(int codePoint) {
        if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t') {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "body must not contain control characters other than newline and tab");
        }
    }

    /** Server-computed ownership: caller id vs author id — never name text. */
    private static PublicCommentEntry toPublicEntry(VisibleCommentRow row, Long callerUserId) {
        return new PublicCommentEntry(
                row.commentId(),
                row.authorName(),
                row.body(),
                row.createdAt(),
                row.editedAt(),
                callerUserId != null && callerUserId.equals(row.authorUserId()));
    }

    static CommentResponse toResponse(CommunityComment comment, String authorName) {
        return new CommentResponse(
                comment.getId(),
                comment.getPublicationId(),
                authorName,
                comment.getBody(),
                comment.getCreatedAt(),
                comment.getEditedAt());
    }

    static CommunityCommentReportResponse toResponse(CommunityCommentReport report) {
        return new CommunityCommentReportResponse(
                report.getId(),
                report.getCommentId(),
                report.getReporterUserId(),
                report.getReasonCode().name(),
                report.getDetails(),
                report.getStatus().name(),
                report.getCreatedAt());
    }
}
