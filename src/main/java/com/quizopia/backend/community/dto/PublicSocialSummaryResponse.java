package com.quizopia.backend.community.dto;

import java.math.BigDecimal;

/**
 * Body of the anonymous {@code GET
 * /api/public/community/publications/{id}/social-summary} (ADR-007 §5,
 * issue #65): aggregates ONLY — {@code {likeCount, average,
 * ratingCount, commentCount}} exactly. The type itself carries no
 * caller-state fields, so a guest payload can never leak {@code liked}
 * or {@code myRating} for anyone.
 */
public record PublicSocialSummaryResponse(
        long likeCount,
        BigDecimal average,
        long ratingCount,
        long commentCount) {
}
