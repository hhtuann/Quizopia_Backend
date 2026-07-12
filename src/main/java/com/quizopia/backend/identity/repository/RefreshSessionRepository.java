package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.RefreshSession;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the {@link RefreshSession} entity, keyed by {@link UUID}.
 *
 * <p>Lookups intentionally do not filter on {@code revokedAt}, {@code expiresAt}
 * or user status: a session that has been revoked or expired is still returned
 * so the service can detect refresh-token reuse and judge validity itself.
 * Bulk updates revoke still-open sessions and are used for logout-all and
 * reuse detection.
 */
public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /**
     * Finds a refresh session by its token hash, eagerly fetching the owning
     * user.
     *
     * <p>The query deliberately does not filter on {@code revokedAt},
     * {@code expiresAt} or user status. A revoked or expired session is still
     * returned so the service can recognize refresh-token reuse (use of a
     * token after rotation) and decide validity on its own.
     *
     * @param tokenHash the hash of the opaque refresh token to look up
     * @return the matching session with its user initialized, or empty if none
     */
    @Query("""
            select rs
            from RefreshSession rs
            join fetch rs.user
            where rs.tokenHash = :tokenHash
            """)
    Optional<RefreshSession> findByTokenHashWithUser(
            @Param("tokenHash") String tokenHash
    );

    /**
     * Finds a refresh session by its token hash with a pessimistic write lock,
     * eagerly fetching the owning user.
     *
     * <p>Used by refresh-token rotation so that two concurrent refreshes of the
     * same opaque token are serialized by the database row lock: the second
     * caller blocks until the first transaction commits (or the 5 s lock timeout
     * elapses), then observes the rotated (replaced) session and trips reuse
     * detection rather than creating a second live session. As with
     * {@link #findByTokenHashWithUser}, no filtering on {@code revokedAt},
     * {@code expiresAt} or user status is performed.
     *
     * @param tokenHash the hash of the opaque refresh token to look up
     * @return the locked session with its user initialized, or empty if none
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("""
            select rs
            from RefreshSession rs
            join fetch rs.user
            where rs.tokenHash = :tokenHash
            """)
    Optional<RefreshSession> findForUpdateByTokenHashWithUser(
            @Param("tokenHash") String tokenHash
    );

    /**
     * Revokes every still-open session owned by a user.
     *
     * <p>Used for logout-all. Only sessions with {@code revokedAt IS NULL} are
     * affected; {@code expiresAt} is not considered so that unrevoked but
     * expired sessions are also marked revoked. Both {@code revokedAt} and
     * {@code revokeReason} are updated together. Logout of the current session
     * only is handled by mutating the loaded entity, not by this bulk update.
     *
     * @param userId    the user whose sessions should be revoked
     * @param revokedAt the instant to mark as the revocation time
     * @param reason    the reason recorded for the revocation
     * @return the number of sessions revoked by this update
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshSession rs
            set rs.revokedAt = :revokedAt,
                rs.revokeReason = :reason
            where rs.user.id = :userId
              and rs.revokedAt is null
            """)
    int revokeUnrevokedByUserId(
            @Param("userId") Long userId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason
    );

    /**
     * Revokes every still-open session in a token-rotation family.
     *
     * <p>Used for refresh-token reuse detection: when a token from a family is
     * presented again after it has been rotated, all remaining open sessions
     * in that family are revoked. Only sessions with {@code revokedAt IS NULL}
     * are affected; {@code expiresAt} is not considered. Both {@code revokedAt}
     * and {@code revokeReason} are updated together.
     *
     * @param familyId  the rotation family whose sessions should be revoked
     * @param revokedAt the instant to mark as the revocation time
     * @param reason    the reason recorded for the revocation
     * @return the number of sessions revoked by this update
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshSession rs
            set rs.revokedAt = :revokedAt,
                rs.revokeReason = :reason
            where rs.familyId = :familyId
              and rs.revokedAt is null
            """)
    int revokeUnrevokedByFamilyId(
            @Param("familyId") UUID familyId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason
    );
}
