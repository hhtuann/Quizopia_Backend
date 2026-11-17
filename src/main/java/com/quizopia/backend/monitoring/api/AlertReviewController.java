package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AlertReviewService;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewAttemptPageResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewHistoryResponse;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewRequest;
import com.quizopia.backend.monitoring.dto.AlertReviewDtos.AlertReviewResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P4-E alert review surface (issue #96, ADR-010 §2.2):
 *
 * <ul>
 *   <li>{@code POST /api/monitoring/alerts/{alertId}/reviews} — append one
 *       review action ({@code Idempotency-Key} header for exactly-once
 *       retries; {@code expectedVersion} for optimistic concurrency — 409
 *       carries the current version).</li>
 *   <li>{@code GET  /api/monitoring/alerts/{alertId}/reviews} — append-only
 *       history + current denormalized state.</li>
 * </ul>
 *
 * <p>Authorization is service-layer (permission codes + session ownership,
 * deny-by-default); reviews never imply a misconduct verdict and never
 * touch grades/attempt/session state.
 */
@RestController
public class AlertReviewController {

    private final AlertReviewService reviewService;

    public AlertReviewController(AlertReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/api/monitoring/alerts/{alertId}/reviews")
    public ResponseEntity<AlertReviewResponse> review(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long alertId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody AlertReviewRequest request) {
        AlertReviewResponse response = reviewService.review(
                Long.valueOf(jwt.getSubject()),
                alertId,
                request.expectedVersion(),
                request.action(),
                request.note(),
                idempotencyKey);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/api/monitoring/alerts/{alertId}/reviews")
    public AlertReviewHistoryResponse history(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long alertId) {
        return reviewService.history(Long.valueOf(jwt.getSubject()), alertId);
    }

    /**
     * F11 drilldown: cursor-paged attempt-scoped review history (the
     * report's completeness path). Same read gate as the alert feed.
     */
    @GetMapping("/api/attempts/{attemptId}/monitoring/reviews")
    public AlertReviewAttemptPageResponse attemptReviews(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long attemptId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return reviewService.attemptReviews(Long.valueOf(jwt.getSubject()),
                attemptId, cursor, limit);
    }
}
