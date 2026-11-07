package com.quizopia.backend.identity.repository;

import com.quizopia.backend.identity.domain.model.Role;
import com.quizopia.backend.identity.domain.model.UserRole;
import com.quizopia.backend.identity.domain.model.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for the {@link UserRole} entity, keyed by the composite
 * {@link UserRoleId} of {@code (user_id, role_id)}.
 *
 * <p>Queries return only the codes of roles that are still effective for a
 * given user, rather than the {@link UserRole} or {@link Role} entities.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    /**
     * Returns the codes of all roles currently effective for a user.
     *
     * <p>An assignment is effective when {@code expiresAt} is {@code null}
     * (valid indefinitely) or strictly greater than {@code now}. Assignments
     * that have expired are excluded. The instant {@code now} is supplied by
     * the application instead of relying on the database clock.
     *
     * @param userId the user identifier
     * @param now    the instant to evaluate expiry against
     * @return the role codes that are still effective for the user
     */
    @Query("""
            select r.code
            from UserRole ur
            join ur.role r
            where ur.user.id = :userId
              and (ur.expiresAt is null or ur.expiresAt > :now)
            """)
    List<String> findActiveRoleCodesByUserId(
            @Param("userId") Long userId,
            @Param("now") Instant now
    );

    /**
     * Inserts a role assignment only when absent. Returns 1 when this call
     * created the row, 0 when it already existed. Used by Google provisioning
     * and the teacher self-upgrade: unlike a JPA save that violates the
     * (user_id, role_id) primary key under concurrency, this never throws —
     * concurrent callers converge on exactly one row without marking any
     * transaction rollback-only.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at)
            VALUES (:userId, :roleId, :userId, now())
            ON CONFLICT (user_id, role_id) DO NOTHING
            """, nativeQuery = true)
    int insertAssignmentIfAbsent(
            @Param("userId") Long userId,
            @Param("roleId") Long roleId);

    /**
     * Upsert for the teacher self-upgrade (review M2-01). Three outcomes:
     * <ul>
     *   <li>no row → INSERT (returns 1, "granted");</li>
     *   <li>row exists but is EXPIRED (expires_at in the past) → reactivation
     *       UPDATE clears expires_at (returns 1, "reactivated");</li>
     *   <li>row exists and is still active → nothing (returns 0,
     *       "idempotent") — never touches a live assignment.</li>
     * </ul>
     * Atomic under the (user_id, role_id) primary key: concurrent callers
     * converge without constraint violations or rollback-only transactions.
     */
    /** True when the user holds a terminal (already-expired) assignment for the role. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND r.code = :roleCode
                  AND ur.expires_at IS NOT NULL AND ur.expires_at <= now()
            )
            """, nativeQuery = true)
    boolean hasExpiredAssignment(
            @Param("userId") Long userId,
            @Param("roleCode") String roleCode);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at)
            VALUES (:userId, :roleId, :userId, now())
            ON CONFLICT (user_id, role_id) DO UPDATE
               SET expires_at = NULL,
                   assigned_at = now(),
                   assigned_by = EXCLUDED.assigned_by
             WHERE user_roles.expires_at IS NOT NULL
               AND user_roles.expires_at <= now()
            """, nativeQuery = true)

    int grantOrReactivateAssignment(
            @Param("userId") Long userId,
            @Param("roleId") Long roleId);
}
