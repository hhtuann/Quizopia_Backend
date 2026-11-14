package com.quizopia.backend.monitoring.repository;

import com.quizopia.backend.monitoring.domain.model.MonitoringPreflight;
import com.quizopia.backend.monitoring.domain.model.PreflightStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MonitoringPreflight} (V37
 * {@code monitoring_preflights}).
 *
 * <p><b>One-time lease spend:</b> {@link #consumeVerified} performs the
 * guarded atomic transition VERIFIED → CONSUMED ({@code WHERE status =
 * 'VERIFIED'}). A return value ≠ 1 means a concurrent start already spent
 * the lease — the caller MUST treat the start as {@code
 * MONITORING_PREFLIGHT_USED}. It is only ever invoked while holding the
 * preflight row lock from {@link #findByLeaseHashForUpdate}, under the
 * session row lock (lock order: session → preflight → attempt insert).
 */
public interface MonitoringPreflightRepository extends JpaRepository<MonitoringPreflight, UUID> {

    /**
     * Pessimistic-write lock on the preflight owning the given lease hash —
     * the lease-consumption critical section of the monitored start gate.
     * The caller MUST already hold the session row lock (start transaction).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM MonitoringPreflight p WHERE p.leaseHash = :leaseHash")
    Optional<MonitoringPreflight> findByLeaseHashForUpdate(@Param("leaseHash") String leaseHash);

    /**
     * Atomic one-time lease spend: VERIFIED → CONSUMED with the consumption
     * timestamp. Returns 0 when the lease was already consumed (concurrent
     * double-start guard) — callers translate that into
     * {@code MONITORING_PREFLIGHT_USED}.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE MonitoringPreflight p SET p.status = :consumed, p.consumedAt = :now "
            + "WHERE p.id = :id AND p.status = :verified")
    int consumeVerified(@Param("id") UUID id,
                        @Param("verified") PreflightStatus verified,
                        @Param("consumed") PreflightStatus consumed,
                        @Param("now") Instant now);

    /**
     * Status-guarded verify transition (first grant ACTIVE→VERIFIED or lease
     * rotation VERIFIED→VERIFIED): 0 rows when the preflight meanwhile moved
     * to a terminal state (CONSUMED/EXPIRED/REVOKED) — the caller re-reads
     * and rejects, so a terminal state can never be resurrected.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MonitoringPreflight p SET p.status = :verified, p.leaseHash = :leaseHash, "
            + "p.leaseExpiresAt = :leaseExpiresAt, p.verifiedTrackSid = :trackSid "
            + "WHERE p.id = :id AND p.status IN :verifiable")
    int markVerifiedGuarded(@Param("id") UUID id,
                            @Param("verifiable") java.util.List<PreflightStatus> verifiable,
                            @Param("verified") PreflightStatus verified,
                            @Param("leaseHash") String leaseHash,
                            @Param("leaseExpiresAt") Instant leaseExpiresAt,
                            @Param("trackSid") String trackSid);

    /**
     * Supersedes the caller's prior ACTIVE preflights (a new preflight for
     * the same (session, user) makes the older ones stale). VERIFIED
     * preflights keep their leases — only the never-verified ACTIVE rows
     * expire.
     */
    @Modifying
    @Query("UPDATE MonitoringPreflight p SET p.status = :expired "
            + "WHERE p.examSessionId = :sessionId AND p.userId = :userId AND p.status = :active")
    int expireActivePreflights(@Param("sessionId") Long sessionId,
                               @Param("userId") Long userId,
                               @Param("active") PreflightStatus active,
                               @Param("expired") PreflightStatus expired);
}
