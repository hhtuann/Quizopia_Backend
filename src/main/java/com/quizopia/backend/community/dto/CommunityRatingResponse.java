package com.quizopia.backend.community.dto;

import java.math.BigDecimal;

/**
 * Body of {@code PUT/DELETE /api/community/publications/{id}/rating}
 * (ADR-007 §5, issue #65): 201 on the first rating / 200 on update,
 * 200 always on DELETE — {@code {average, ratingCount, myRating}}
 * exactly. {@code average} is {@code null} (never {@code 0}) when
 * {@code ratingCount} is 0; {@code myRating} is {@code null} after
 * DELETE or when the caller never rated.
 */
public record CommunityRatingResponse(
        BigDecimal average,
        long ratingCount,
        Integer myRating) {
}
