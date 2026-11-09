package com.quizopia.backend.community.application;

import com.quizopia.backend.community.dto.CommunitySocialSummaryResponse;
import com.quizopia.backend.community.dto.PublicSocialSummaryResponse;
import com.quizopia.backend.community.exception.CommunityErrorCode;
import com.quizopia.backend.community.exception.CommunityException;
import com.quizopia.backend.community.repository.CommunityLikeRepository;
import com.quizopia.backend.community.repository.CommunityRatingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Like + rating interactions and the social-summary aggregates
 * (ADR-007 §3/§5/§6, issue #65). Writes follow the module-wide fixed
 * order — do not reorder:
 *
 * <ol>
 *   <li><b>semantic validation</b> — bounded input never admits;</li>
 *   <li><b>rate admission</b> — LIKE 60/h / RATING 30/h user buckets as
 *       the FIRST statement of the write transaction (§5; denied
 *       requests still consume, bucket locks stay leaf-most);</li>
 *   <li><b>permission gate</b> — any ACTIVE user holding the effective
 *       permission (STUDENT and TEACHER both qualify; role-less callers
 *       are 403);</li>
 *   <li><b>§4 visibility predicate → 404</b> — liking/rating an
 *       invisible/absent publication is anti-enumeration 404;</li>
 *   <li><b>rating only: self-rating check BEFORE the upsert</b> —
 *       {@code publisher_user_id == caller} → 409
 *       COMMUNITY_SELF_RATING_NOT_ALLOWED (the publisher id is
 *       immutable, so the check is sound under race; publishers MAY
 *       like their own quiz);</li>
 *   <li><b>upsert</b> — like: {@code INSERT … ON CONFLICT DO NOTHING} +
 *       rowcount (bookmark precedent); rating: the single native
 *       {@code … DO UPDATE … RETURNING (xmax = 0)} statement.</li>
 * </ol>
 *
 * <p>DELETEs skip the visibility probe (a like/rating is removed
 * whatever happened to its source — bookmark-DELETE precedent; §5's
 * "200 always") and are idempotent by shape. Aggregates are computed
 * on read (§3, deterministic): likeCount via the likes index,
 * ratingCount/average via the ratings covering index
 * ({@code ROUND(AVG(rating), 2)}, JSON {@code null} — never {@code 0} —
 * at zero ratings), commentCount as the VISIBLE-only count that shares
 * the public list predicate, so summary and list totals can never
 * diverge. No denormalized counters, no N+1.
 */
@Service
public class CommunitySocialService {

    static final String PERMISSION_LIKE = "COMMUNITY_LIKE";
    static final String PERMISSION_RATE = "COMMUNITY_RATE";

    /**
     * §6 rating upsert, one native statement: insert or update keyed by
     * (user_id, publication_id) with {@code (xmax = 0)} as the created
     * flag — race-correct under PostgreSQL MVCC (an updated row carries
     * the writing transaction's xid; a fresh insert has xmax 0).
     */
    private static final String RATING_UPSERT = """
            INSERT INTO community_ratings (user_id, publication_id, rating, created_at, updated_at)
            VALUES (?, ?, ?, now(), now())
            ON CONFLICT (user_id, publication_id)
            DO UPDATE SET rating = EXCLUDED.rating, updated_at = EXCLUDED.updated_at
            RETURNING (xmax = 0) AS created
            """;

    /** commentCount: the public list predicate — VISIBLE comments only. */
    private static final String VISIBLE_COMMENT_COUNT = """
            SELECT COUNT(*) FROM community_comments
            WHERE publication_id = ? AND state = 'VISIBLE'
            """;

    private final CommunityLikeRepository likes;
    private final CommunityRatingRepository ratings;
    private final CommunityAuthorizationService auth;
    private final CommunityRateLimiter rateLimiter;
    private final CommunityVisibilityStore visibilityStore;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CommunitySocialService(CommunityLikeRepository likes,
            CommunityRatingRepository ratings,
            CommunityAuthorizationService auth,
            CommunityRateLimiter rateLimiter,
            CommunityVisibilityStore visibilityStore,
            JdbcTemplate jdbc,
            Clock clock) {
        this.likes = likes;
        this.ratings = ratings;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.visibilityStore = visibilityStore;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Like outcome: the 201-vs-200 flag plus the recomputed aggregate. */
    public record LikeOutcome(boolean created, long likeCount) {
    }

    /** Rating outcome: the 201-vs-200 flag plus the recomputed aggregates. */
    public record RatingOutcome(boolean created, long ratingCount, BigDecimal average,
            Integer myRating) {
    }

    /** The four §3 aggregates shared by both social-summary routes. */
    public record SocialAggregates(long likeCount, BigDecimal average, long ratingCount,
            long commentCount) {
    }

    // ================================================================
    // like (PUT/DELETE /api/community/publications/{id}/like)
    // ================================================================

    @Transactional
    public LikeOutcome likePublication(Long userId, Long publicationId) {
        // (1) §5 admission — first statement of the write transaction.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.LIKE, userId);

        // (2) Permission gate: ACTIVE + effective COMMUNITY_LIKE.
        auth.requireActiveUserWithPermission(userId, PERMISSION_LIKE);

        // (3) §4 visibility predicate → 404 (never an existence oracle).
        requireVisible(publicationId);

        // (4) Idempotent upsert: 1 affected row → this call created the
        // like (201); 0 → it already existed (200). Concurrent PUTs of
        // the same (user, publication) collapse onto one row.
        int inserted = likes.insertIfAbsent(userId, publicationId, Instant.now(clock));
        return new LikeOutcome(inserted == 1, likes.countByPublicationId(publicationId));
    }

    @Transactional
    public long unlikePublication(Long userId, Long publicationId) {
        // Same order: admission, then permission. No visibility probe —
        // un-liking works whatever became of the source (200 always).
        rateLimiter.admitUser(CommunityRateLimiter.Policy.LIKE, userId);
        auth.requireActiveUserWithPermission(userId, PERMISSION_LIKE);

        likes.deleteByUserIdAndPublicationId(userId, publicationId);
        return likes.countByPublicationId(publicationId);
    }

    // ================================================================
    // rating (PUT/DELETE /api/community/publications/{id}/rating)
    // ================================================================

    @Transactional
    public RatingOutcome ratePublication(Long userId, Long publicationId, Integer rating) {
        // (0) Semantic validation — out-of-bounds values never admit.
        if (rating == null || rating < 1 || rating > 5) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_VALIDATION_ERROR,
                    "rating must be between 1 and 5");
        }

        // (1) §5 admission — first statement of the write transaction.
        rateLimiter.admitUser(CommunityRateLimiter.Policy.RATING, userId);

        // (2) Permission gate: ACTIVE + effective COMMUNITY_RATE.
        auth.requireActiveUserWithPermission(userId, PERMISSION_RATE);

        // (3) §4 predicate → 404, and (4) self-rating check BEFORE the
        // upsert (§6): publisher_user_id is immutable → no TOCTOU. The
        // predicate-gated read is one statement in the visibility store.
        long publisherId = visibilityStore.findVisiblePublisherId(publicationId)
                .orElseThrow(() -> new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND));
        if (publisherId == userId) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_SELF_RATING_NOT_ALLOWED);
        }

        // (5) Single-statement upsert; created is race-correct (xmax).
        boolean created = upsertRating(userId, publicationId, rating);

        // (6) Aggregates recomputed on read after the upsert (§3).
        long count = ratings.countByPublicationId(publicationId);
        BigDecimal average = ratings.averageRatingFor(publicationId).orElse(null);
        return new RatingOutcome(created, count, average, rating);
    }

    @Transactional
    public RatingOutcome deleteRating(Long userId, Long publicationId) {
        // Same order: admission, then permission. No visibility probe —
        // removing your own rating works whatever became of the source
        // (200 always, idempotent by shape).
        rateLimiter.admitUser(CommunityRateLimiter.Policy.RATING, userId);
        auth.requireActiveUserWithPermission(userId, PERMISSION_RATE);

        ratings.deleteByUserIdAndPublicationId(userId, publicationId);
        long count = ratings.countByPublicationId(publicationId);
        BigDecimal average = ratings.averageRatingFor(publicationId).orElse(null);
        return new RatingOutcome(false, count, average, null);
    }

    // ================================================================
    // social summaries (GET …/social-summary, both twins)
    // ================================================================

    /**
     * The public twin's aggregates (ADR-007 §5): counts only, never
     * caller state. Admission is the caller's concern
     * ({@code PublicCommunityService}: PUBLIC_READ_IP posture —
     * anonymous IP-keyed, authenticated user-keyed); this method only
     * enforces the §4 predicate and computes.
     */
    public PublicSocialSummaryResponse publicSummary(long publicationId) {
        requireVisible(publicationId);
        SocialAggregates aggregates = aggregates(publicationId);
        return new PublicSocialSummaryResponse(aggregates.likeCount(), aggregates.average(),
                aggregates.ratingCount(), aggregates.commentCount());
    }

    /**
     * The authenticated twin: aggregates PLUS the caller's own states
     * only ({@code liked}, {@code myRating}) — no other user's id
     * anywhere. Admitted under the same PUBLIC_READ_IP policy on the
     * user dimension (§5), self-committing before the read.
     */
    public CommunitySocialSummaryResponse authenticatedSummary(Long userId, long publicationId) {
        rateLimiter.admitUser(CommunityRateLimiter.Policy.PUBLIC_READ_IP, userId);
        requireVisible(publicationId);

        SocialAggregates aggregates = aggregates(publicationId);
        boolean liked = likes.findByUserIdAndPublicationId(userId, publicationId).isPresent();
        Integer myRating = ratings.findByUserIdAndPublicationId(userId, publicationId)
                .map(communityRating -> (int) communityRating.getRating())
                .orElse(null);
        return new CommunitySocialSummaryResponse(aggregates.likeCount(), aggregates.average(),
                aggregates.ratingCount(), aggregates.commentCount(), liked, myRating);
    }

    // ============================================================
    // internals
    // ============================================================

    /** §4 predicate → anti-enumeration 404 (single reused oracle). */
    private void requireVisible(long publicationId) {
        if (!visibilityStore.isVisible(publicationId)) {
            throw new CommunityException(CommunityErrorCode.COMMUNITY_NOT_FOUND);
        }
    }

    /** The §3 aggregates, each from its frozen index. */
    private SocialAggregates aggregates(long publicationId) {
        long likeCount = likes.countByPublicationId(publicationId);
        long ratingCount = ratings.countByPublicationId(publicationId);
        BigDecimal average = ratings.averageRatingFor(publicationId).orElse(null);
        Long commentCount = jdbc.queryForObject(
                VISIBLE_COMMENT_COUNT, Long.class, publicationId);
        return new SocialAggregates(likeCount, average, ratingCount,
                commentCount == null ? 0L : commentCount);
    }

    /**
     * The §6 single-statement rating upsert on JdbcTemplate (Spring
     * Data's modifying-query path discards RETURNING rows).
     * {@code (xmax = 0)} is true iff this statement inserted the row —
     * false when an existing row was updated, even under a lost race.
     */
    private boolean upsertRating(long userId, long publicationId, int rating) {
        Boolean created = jdbc.queryForObject(
                RATING_UPSERT, Boolean.class, userId, publicationId, (short) rating);
        return Boolean.TRUE.equals(created);
    }
}
