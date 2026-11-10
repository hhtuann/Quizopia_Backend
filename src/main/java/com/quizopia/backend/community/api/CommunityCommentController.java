package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunityCommentService;
import com.quizopia.backend.community.domain.model.CommunityComment;
import com.quizopia.backend.community.dto.CommentResponse;
import com.quizopia.backend.community.dto.CommunityCommentReportResponse;
import com.quizopia.backend.community.dto.CreateCommentRequest;
import com.quizopia.backend.community.dto.ReportCommentRequest;
import com.quizopia.backend.community.dto.UpdateCommentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comment API (ADR-007 §5, issue #66): create on a publication, owner
 * PATCH/DELETE, comment reports. Authorization is service-layer
 * (deny-by-default DB checks); the security chain only authenticates.
 * Every response carries {@code Cache-Control: no-store} (§7).
 *
 * <p>Create: 201 with the comment DTO (alias §4; 409
 * COMMUNITY_ALIAS_TAKEN on a different-user ci collision). PATCH:
 * owner-only via the §6 state-guarded CAS — foreign/absent 404s are
 * byte-identical, own-but-not-VISIBLE is 409
 * COMMUNITY_COMMENT_STATE_INVALID. DELETE: idempotent 200 over any own
 * state. Reports: fixed order admission → permission → VISIBLE + §4 →
 * dedupe (200 with the same report) → 201.
 */
@RestController
@RequestMapping("/api/community")
public class CommunityCommentController {

    private final CommunityCommentService commentService;

    public CommunityCommentController(CommunityCommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping("/publications/{publicationId}/comments")
    public ResponseEntity<CommentResponse> create(
            @PathVariable Long publicationId,
            @RequestBody(required = false) CreateCommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        CreateCommentRequest safe = request == null
                ? new CreateCommentRequest(null, null) : request;
        CommunityComment comment = commentService.createComment(
                Long.valueOf(jwt.getSubject()), publicationId, safe);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(toResponse(comment));
    }

    @PatchMapping("/comments/{commentId}")
    public ResponseEntity<CommentResponse> update(
            @PathVariable Long commentId,
            @RequestBody(required = false) UpdateCommentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UpdateCommentRequest safe = request == null ? new UpdateCommentRequest(null) : request;
        CommunityComment comment = commentService.updateComment(
                Long.valueOf(jwt.getSubject()), commentId, safe);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toResponse(comment));
    }

    /** Idempotent owner soft-delete (§5): 200 always once owned, no body. */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long commentId,
            @AuthenticationPrincipal Jwt jwt) {
        commentService.deleteComment(Long.valueOf(jwt.getSubject()), commentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    /** POST /api/community/comments/{commentId}/reports — 201 first / 200 dedupe. */
    @PostMapping("/comments/{commentId}/reports")
    public ResponseEntity<CommunityCommentReportResponse> report(
            @PathVariable Long commentId,
            @Valid @RequestBody ReportCommentRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        CommunityCommentService.SubmittedCommentReport submitted = commentService.reportComment(
                Long.valueOf(jwt.getSubject()), commentId, request, httpRequest.getRemoteAddr());
        return ResponseEntity.status(submitted.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(submitted.report());
    }

    private static CommentResponse toResponse(CommunityComment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getPublicationId(),
                comment.getAuthorAlias(),
                comment.getBody(),
                comment.getCreatedAt(),
                comment.getEditedAt());
    }
}
