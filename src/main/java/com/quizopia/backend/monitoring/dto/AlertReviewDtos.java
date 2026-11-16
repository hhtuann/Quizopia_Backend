package com.quizopia.backend.monitoring.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;


/**
 * P4-E alert review DTOs (issue #96, ADR-010 §2.2 / design §3.3–3.4).
 *
 * <p>Reviews are human annotations of AI observations — the response types
 * never carry detector fields (those stay on the alert feed DTO) and never
 * imply a misconduct verdict.
 */
public final class AlertReviewDtos {

    private AlertReviewDtos() {
    }

    /** Body of {@code POST /api/monitoring/alerts/{alertId}/reviews}. */
    public record AlertReviewRequest(
            @NotNull @PositiveOrZero Integer expectedVersion,
            @NotNull @NotBlank @Size(max = 24) String action,
            /** Optional teacher note; content-safe bounded length (V42 CHECK). */
            @Size(max = 1000) String note) {
    }

    /** Outcome of a review append or idempotent replay. */
    public record AlertReviewResponse(
            Long alertId,
            int reviewVersion,
            String reviewStatus,
            String action,
            String note,
            Long reviewerUserId,
            Instant reviewedAt,
            boolean idempotentReplay) {
    }

    /** Current denormalized review state (UNREVIEWED-shaped when none). */
    public record CurrentReview(
            String status,
            int version,
            Instant reviewedAt,
            String reviewerName) {
    }

    /** One ledger row (append-only history). */
    public record ReviewRow(
            long reviewId,
            int version,
            String action,
            String note,
            String reviewerName,
            Instant reviewedAt) {
    }

    /** Body of {@code GET /api/monitoring/alerts/{alertId}/reviews}. */
    public record AlertReviewHistoryResponse(
            Long alertId,
            CurrentReview currentReview,
            List<ReviewRow> history) {
    }
}
