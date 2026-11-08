package com.quizopia.backend.community.repository;

import com.quizopia.backend.community.domain.model.CommunityRateBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository backing {@code CommunityRateLimiter} with the exact V23
 * mechanics (ADR-006 §9): {@code ON CONFLICT (bucket_key) DO NOTHING}
 * upsert → {@code SELECT … FOR UPDATE} row lock → {@code UPDATE}. The
 * {@code FOR UPDATE} lives in the SQL itself so the lock is taken by the
 * database regardless of JPA lock-mode hint support for native queries.
 */
public interface CommunityRateBucketRepository extends JpaRepository<CommunityRateBucket, String> {

    /** Creates the bucket row when absent; never raises on races. */
    @Modifying
    @Query(value = "INSERT INTO community_rate_buckets"
            + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
            + " VALUES (:bucketKey, :windowStart, 0, NULL, :expiresAt)"
            + " ON CONFLICT (bucket_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("bucketKey") String bucketKey,
                       @Param("windowStart") Instant windowStart,
                       @Param("expiresAt") Instant expiresAt);

    /** Locks and loads the bucket row (must run inside the caller's transaction). */
    @Query(value = "SELECT * FROM community_rate_buckets WHERE bucket_key = :bucketKey FOR UPDATE",
            nativeQuery = true)
    Optional<CommunityRateBucket> findForUpdateByKey(@Param("bucketKey") String bucketKey);

    /** Advances the fixed window; attempts saturate at Integer.MAX_VALUE. */
    @Modifying
    @Query(value = "UPDATE community_rate_buckets"
            + " SET window_start = :windowStart, attempts = :attempts, expires_at = :expiresAt"
            + " WHERE bucket_key = :bucketKey", nativeQuery = true)
    int updateWindow(@Param("bucketKey") String bucketKey,
                     @Param("windowStart") Instant windowStart,
                     @Param("attempts") int attempts,
                     @Param("expiresAt") Instant expiresAt);

    /** Observability: when this bucket last admitted a request. */
    @Modifying
    @Query(value = "UPDATE community_rate_buckets SET last_admitted_at = :admittedAt"
            + " WHERE bucket_key = :bucketKey", nativeQuery = true)
    int markAdmitted(@Param("bucketKey") String bucketKey, @Param("admittedAt") Instant admittedAt);
}
