package com.quizopia.backend.community.dto;

import java.math.BigDecimal;

/**
 * Body of {@code GET /api/community/publications/{id}/social-summary}
 * (ADR-007 §5, issue #65): the on-read aggregates PLUS the caller's own
 * interaction state — {@code {likeCount, average, ratingCount,
 * commentCount, liked, myRating}} exactly. {@code average} is
 * {@code null} when {@code ratingCount} is 0; {@code commentCount}
 * counts VISIBLE comments only (same predicate as the public list).
 * No other user's id or state ever appears.
 */
public record CommunitySocialSummaryResponse(
        long likeCount,
        BigDecimal average,
        long ratingCount,
        long commentCount,
        boolean liked,
        Integer myRating) {
}
