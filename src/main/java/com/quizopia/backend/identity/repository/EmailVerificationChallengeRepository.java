package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.EmailChallengePurpose;
import com.quizopia.backend.identity.domain.model.EmailVerificationChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository for {@link EmailVerificationChallenge}.
 *
 * <p>Only PENDING challenges are candidates for verification. The pessimistic
 * lock serializes concurrent verifications of the same challenge so the
 * one-time-use transition happens exactly once, and serializes the
 * supersede-on-resend against a concurrent verify (ADR-002 §2).
 */
public interface EmailVerificationChallengeRepository extends JpaRepository<EmailVerificationChallenge, Long> {

    /**
     * Locks the single PENDING challenge of a user+purpose (verify and
     * supersede paths). The partial unique index
     * {@code uk_email_challenges_pending} guarantees at most one such row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from EmailVerificationChallenge c
            where c.user.id = :userId
              and c.purpose = :purpose
              and c.status = com.quizopia.backend.identity.domain.model.EmailChallengeStatus.PENDING
            """)
    Optional<EmailVerificationChallenge> findPendingForUpdate(
            @Param("userId") Long userId,
            @Param("purpose") EmailChallengePurpose purpose);

    /** Most recent challenge of a user+purpose (resend cooldown check). */
    Optional<EmailVerificationChallenge> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(
            Long userId, EmailChallengePurpose purpose);

    /** Challenges issued for one email within the window (rate limiting). */
    @Query("""
            select count(c) from EmailVerificationChallenge c
            where lower(c.user.email) = lower(:email)
              and c.createdAt >= :since
            """)
    long countByUserEmailSince(
            @Param("email") String email,
            @Param("since") Instant since);

    /** Challenges issued from one IP within the window (rate limiting). */
    long countByRequestIpAndCreatedAtGreaterThanEqual(String requestIp, Instant since);

    /** Deletes terminal challenges older than the cutoff (cleanup sweeper). */
    @Modifying
    @Query("""
            delete from EmailVerificationChallenge c
            where c.status <> com.quizopia.backend.identity.domain.model.EmailChallengeStatus.PENDING
              and c.updatedAt < :cutoff
            """)
    int deleteTerminalOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Targeted delivery-completion update (reviewer blocker fix): sets ONLY
     * the delivered flag by id — never merges a possibly-stale entity
     * snapshot over concurrent lifecycle changes (attempts, terminal
     * transitions, supersession) that may have committed while SMTP ran.
     */
    @Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE email_verification_challenges
               SET delivered = TRUE, updated_at = now()
             WHERE id = :challengeId
            """, nativeQuery = true)
    void markDeliveredById(@Param("challengeId") Long challengeId);

    /** Fails PENDING challenges past their deadline (sweeper-only path). */
    @Modifying
    @Query("""
            update EmailVerificationChallenge c
            set c.status = com.quizopia.backend.identity.domain.model.EmailChallengeStatus.FAILED,
                c.failedAt = :now
            where c.status = com.quizopia.backend.identity.domain.model.EmailChallengeStatus.PENDING
              and c.expiresAt <= :now
            """)
    int failExpiredPending(@Param("now") Instant now);
}
