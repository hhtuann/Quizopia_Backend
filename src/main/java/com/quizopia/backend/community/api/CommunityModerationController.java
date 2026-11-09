package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunityModerationService;
import com.quizopia.backend.community.dto.ModerationCommentEntry;
import com.quizopia.backend.community.dto.ModerationCommentHistoryEntry;
import com.quizopia.backend.community.dto.ModerationCommentReportEntry;
import com.quizopia.backend.community.dto.ModerationGrantResponse;
import com.quizopia.backend.community.dto.ModerationHistoryEntry;
import com.quizopia.backend.community.dto.ModerationReasonRequest;
import com.quizopia.backend.community.dto.ModerationPublicationSummary;
import com.quizopia.backend.community.dto.ModerationReportEntry;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.question.dto.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Moderator API (ADR-006 §5/§7, issue #42) under
 * {@code /api/community/moderation}: grant gate, report queue,
 * hide/restore, report dismiss/resolve, per-publication history.
 *
 * <p>The security chain only authenticates ({@code /api/community/**}
 * is authenticated); authorization is the service-layer
 * effective-permission check (COMMUNITY_MODERATION + ACTIVE, live DB
 * resolution per request). No route here can grant or revoke moderator
 * power — that is the operator runbook's job (§5). Every response
 * carries {@code Cache-Control: no-store} (§7).
 */
@RestController
@RequestMapping("/api/community/moderation")
public class CommunityModerationController {

    private final CommunityModerationService moderationService;

    public CommunityModerationController(CommunityModerationService moderationService) {
        this.moderationService = moderationService;
    }

    /** FE console gate: 200 {granted:true} with the grant, 403 without. */
    @GetMapping("/grant")
    public ResponseEntity<ModerationGrantResponse> grant(
            @AuthenticationPrincipal Jwt jwt) {
        Long userId = userId(jwt);
        if (!moderationService.hasModerationGrant(userId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_ACCESS_DENIED);
        }
        return noStore(new ModerationGrantResponse(true));
    }

    @GetMapping("/reports")
    public ResponseEntity<PageResponse<ModerationReportEntry>> reports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(moderationService.reportQueue(userId(jwt), status, page, size));
    }

    @PostMapping("/publications/{publicationId}/hide")
    public ResponseEntity<ModerationPublicationSummary> hide(
            @PathVariable Long publicationId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.hide(userId(jwt), publicationId, reasonOf(request)));
    }

    @PostMapping("/publications/{publicationId}/restore")
    public ResponseEntity<ModerationPublicationSummary> restore(
            @PathVariable Long publicationId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.restore(userId(jwt), publicationId, reasonOf(request)));
    }

    @PostMapping("/reports/{reportId}/dismiss")
    public ResponseEntity<ModerationReportEntry> dismiss(
            @PathVariable Long reportId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.dismissReport(userId(jwt), reportId, noteOf(request)));
    }

    @PostMapping("/reports/{reportId}/resolve")
    public ResponseEntity<ModerationReportEntry> resolve(
            @PathVariable Long reportId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.resolveReport(userId(jwt), reportId, noteOf(request)));
    }

    @GetMapping("/publications/{publicationId}/history")
    public ResponseEntity<List<ModerationHistoryEntry>> history(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.publicationHistory(userId(jwt), publicationId));
    }

    // ============================================================
    // Comment moderation (ADR-007 §5, issue #66) — mirrors the
    // publication routes; the comment twins take ONLY the comment /
    // comment-report row locks, never the publication row.
    // ============================================================

    @GetMapping("/comment-reports")
    public ResponseEntity<PageResponse<ModerationCommentReportEntry>> commentReports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(moderationService.commentReportQueue(userId(jwt), status, page, size));
    }

    @PostMapping("/comments/{commentId}/hide")
    public ResponseEntity<ModerationCommentEntry> hideComment(
            @PathVariable Long commentId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.hideComment(userId(jwt), commentId, reasonOf(request)));
    }

    @PostMapping("/comments/{commentId}/restore")
    public ResponseEntity<ModerationCommentEntry> restoreComment(
            @PathVariable Long commentId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.restoreComment(userId(jwt), commentId, reasonOf(request)));
    }

    @PostMapping("/comment-reports/{reportId}/dismiss")
    public ResponseEntity<ModerationCommentReportEntry> dismissCommentReport(
            @PathVariable Long reportId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.dismissCommentReport(
                userId(jwt), reportId, noteOf(request)));
    }

    @PostMapping("/comment-reports/{reportId}/resolve")
    public ResponseEntity<ModerationCommentReportEntry> resolveCommentReport(
            @PathVariable Long reportId,
            @RequestBody(required = false) ModerationReasonRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.resolveCommentReport(
                userId(jwt), reportId, noteOf(request)));
    }

    @GetMapping("/comments/{commentId}/history")
    public ResponseEntity<List<ModerationCommentHistoryEntry>> commentHistory(
            @PathVariable Long commentId,
            @AuthenticationPrincipal Jwt jwt) {
        return noStore(moderationService.commentHistory(userId(jwt), commentId));
    }

    /** Hide/restore read {@code reason}; a body-less POST is a 400. */
    private static String reasonOf(ModerationReasonRequest request) {
        return request == null ? null : request.reason();
    }

    /** Dismiss/resolve read the optional {@code note}. */
    private static String noteOf(ModerationReasonRequest request) {
        return request == null ? null : request.note();
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
