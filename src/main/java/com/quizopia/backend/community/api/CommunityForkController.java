package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunityForkService;
import com.quizopia.backend.community.dto.CommunityForkResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Community fork API (ADR-006 §7-§8, issue #47):
 * {@code POST /api/community/publications/{id}/fork} with the required
 * {@code Idempotency-Key} header (8-128 printable ASCII). 201 on first
 * creation, 200 on the idempotent replay (same actor + key +
 * publication), 409 when the key was spent on a different publication.
 * Authorization is service-layer (deny-by-default DB checks — ACTIVE
 * TEACHER holding COMMUNITY_FORK and EXAM_CREATE); the security chain
 * only authenticates. Every response carries
 * {@code Cache-Control: no-store} (§7).
 */
@RestController
@RequestMapping("/api/community")
public class CommunityForkController {

    private final CommunityForkService forkService;

    public CommunityForkController(CommunityForkService forkService) {
        this.forkService = forkService;
    }

    @PostMapping("/publications/{publicationId}/fork")
    public ResponseEntity<CommunityForkResponse> fork(
            @PathVariable Long publicationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        CommunityForkService.ForkOutcome outcome = forkService
                .fork(Long.valueOf(jwt.getSubject()), publicationId, idempotencyKey);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(new CommunityForkResponse(outcome.targetExamId(), outcome.sourcePublicationId(),
                        outcome.sourceTitle(), outcome.authorAlias(), outcome.forkedAt()));
    }
}
