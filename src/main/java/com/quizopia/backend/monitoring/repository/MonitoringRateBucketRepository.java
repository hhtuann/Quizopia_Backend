package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringRateBucket;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Backs {@code ActivityIngestRateLimiter} with the proven V23/ADR-006 §9
 * mechanics: {@code ON CONFLICT (bucket_key) DO NOTHING} upsert →
 * {@code SELECT … FOR UPDATE} row lock → {@code UPDATE}.
 */
public interface MonitoringRateBucketRepository extends JpaRepository<MonitoringRateBucket, String> {

    @Modifying
    @Query(value = "INSERT INTO monitoring_rate_buckets"
            + " (bucket_key, window_start, attempts, last_admitted_at, expires_at)"
            + " VALUES (:bucketKey, :windowStart, 0, NULL, :expiresAt)"
            + " ON CONFLICT (bucket_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("bucketKey") String bucketKey,
                       @Param("windowStart") Instant windowStart,
                       @Param("expiresAt") Instant expiresAt);

    @Query(value = "SELECT * FROM monitoring_rate_buckets WHERE bucket_key = :bucketKey FOR UPDATE",
            nativeQuery = true)
    Optional<MonitoringRateBucket> findForUpdateByKey(@Param("bucketKey") String bucketKey);

    @Modifying
    @Query(value = "UPDATE monitoring_rate_buckets"
            + " SET window_start = :windowStart, attempts = :attempts, expires_at = :expiresAt"
            + " WHERE bucket_key = :bucketKey", nativeQuery = true)
    int updateWindow(@Param("bucketKey") String bucketKey,
                     @Param("windowStart") Instant windowStart,
                     @Param("attempts") int attempts,
                     @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = "UPDATE monitoring_rate_buckets SET last_admitted_at = :admittedAt"
            + " WHERE bucket_key = :bucketKey", nativeQuery = true)
    int markAdmitted(@Param("bucketKey") String bucketKey,
                     @Param("admittedAt") Instant admittedAt);
}
