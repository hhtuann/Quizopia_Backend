package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Repository for {@link CommunityRating} (ADR-007 §3/§6, issue #65).
 *
 * <p>The upsert itself is a single native statement with
 * {@code RETURNING (xmax = 0) AS created} — Spring Data's modifying
 * query path discards returned rows, so it lives in
 * {@code CommunitySocialService} on JdbcTemplate (§6 mechanism: xmax = 0
 * iff the row was inserted by this statement). This interface carries
 * the owner-key reads, the idempotent DELETE and the
 * {@code ROUND(AVG(rating), 2)} aggregate over the covering index
 * {@code (publication_id) INCLUDE (rating)}.
 */
public interface CommunityRatingRepository
        extends JpaRepository<CommunityRating, CommunityRating.RatingId> {

    /** The caller's own rating, for {@code myRating} in the social summary. */
    Optional<CommunityRating> findByUserIdAndPublicationId(Long userId, Long publicationId);

    /** Idempotent removal by owner key; returns affected rows (0 = was absent). */
    @Modifying
    @Query(value = "DELETE FROM community_ratings"
            + " WHERE user_id = :userId AND publication_id = :publicationId", nativeQuery = true)
    int deleteByUserIdAndPublicationId(@Param("userId") long userId,
                                       @Param("publicationId") long publicationId);

    /** ratingCount aggregate (index-only via the covering index). */
    long countByPublicationId(Long publicationId);

    /**
     * The {@code average} aggregate: {@code ROUND(AVG(rating), 2)} —
     * 2-decimal numeric, or empty when the publication has no ratings
     * (the summary then renders JSON {@code null}, never {@code 0}).
     */
    @Query(value = "SELECT ROUND(AVG(rating), 2) FROM community_ratings"
            + " WHERE publication_id = :publicationId", nativeQuery = true)
    Optional<BigDecimal> averageRatingFor(@Param("publicationId") long publicationId);
}
