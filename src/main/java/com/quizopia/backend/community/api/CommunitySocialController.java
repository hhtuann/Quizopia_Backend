package com.quizopia.backend.community.api;

import com.quizopia.backend.community.application.CommunitySocialService;
import com.quizopia.backend.community.dto.CommunityLikeResponse;
import com.quizopia.backend.community.dto.CommunityRatingRequest;
import com.quizopia.backend.community.dto.CommunityRatingResponse;
import com.quizopia.backend.community.dto.CommunitySocialSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Social interaction API (ADR-007 §5, issue #65):
 * {@code PUT/DELETE /api/community/publications/{id}/like},
 * {@code PUT/DELETE /api/community/publications/{id}/rating} and the
 * authenticated twin {@code GET /api/community/publications/{id}/
 * social-summary}. Authorization is service-layer (deny-by-default DB
 * checks — any ACTIVE user holding the effective COMMUNITY_LIKE /
 * COMMUNITY_RATE, STUDENT and TEACHER alike); the security chain only
 * authenticates. Every response carries {@code Cache-Control: no-store}
 * (§7 — no invalidation story exists).
 *
 * <p>Like PUT: 201 on first like, 200 on the idempotent repeat; §4
 * visibility failure is anti-enumeration 404. Like DELETE: always 200.
 * Rating PUT: publisher of the publication → 409
 * COMMUNITY_SELF_RATING_NOT_ALLOWED (checked before the upsert);
 * otherwise 201 first / 200 update. Rating DELETE: always 200 with
 * {@code myRating:null}. The summary returns aggregates plus the
 * caller's own states ONLY — no other user's id anywhere.
 */
@RestController
@RequestMapping("/api/community")
public class CommunitySocialController {

    private final CommunitySocialService socialService;

    public CommunitySocialController(CommunitySocialService socialService) {
        this.socialService = socialService;
    }

    @PutMapping("/publications/{publicationId}/like")
    public ResponseEntity<CommunityLikeResponse> like(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        CommunitySocialService.LikeOutcome outcome =
                socialService.likePublication(Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(new CommunityLikeResponse(outcome.likeCount(), true));
    }

    @DeleteMapping("/publications/{publicationId}/like")
    public ResponseEntity<CommunityLikeResponse> unlike(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        long likeCount = socialService
                .unlikePublication(Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CommunityLikeResponse(likeCount, false));
    }

    @PutMapping("/publications/{publicationId}/rating")
    public ResponseEntity<CommunityRatingResponse> rate(
            @PathVariable Long publicationId,
            @Valid @RequestBody CommunityRatingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        CommunitySocialService.RatingOutcome outcome = socialService.ratePublication(
                Long.valueOf(jwt.getSubject()), publicationId, request.rating());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(new CommunityRatingResponse(outcome.average(), outcome.ratingCount(),
                        outcome.myRating()));
    }

    @DeleteMapping("/publications/{publicationId}/rating")
    public ResponseEntity<CommunityRatingResponse> deleteRating(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        CommunitySocialService.RatingOutcome outcome = socialService
                .deleteRating(Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CommunityRatingResponse(outcome.average(), outcome.ratingCount(),
                        outcome.myRating()));
    }

    @GetMapping("/publications/{publicationId}/social-summary")
    public ResponseEntity<CommunitySocialSummaryResponse> socialSummary(
            @PathVariable Long publicationId,
            @AuthenticationPrincipal Jwt jwt) {
        CommunitySocialSummaryResponse summary = socialService.authenticatedSummary(
                Long.valueOf(jwt.getSubject()), publicationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summary);
    }
}
